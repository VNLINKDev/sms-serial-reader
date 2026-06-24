package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.SerialPortException;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;
import com.example.sms.telegram.TelegramNotifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class SmsReaderRuntime {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);

    private final SerialPortManager portManager;
    private final ModemInitializer modemInitializer;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;
    private final TelegramNotifier telegramNotifier;
    private final AppConfig appConfig;

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-command-thread");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService redisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-redis-thread");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private ScheduledFuture<?> pollFuture;

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    /**
     * Tin nhắn mới nhất do thread đọc tìm thấy, đang chờ thread Redis lấy đi
     * publish.
     */
    private volatile SmsMessage pendingMessage = null;

    /** Lock dùng để báo hiệu (wait/notify) giữa thread đọc và thread publish. */
    private final Object publishLock = new Object();

    /** transactionId đã publish thành công gần nhất. */
    private volatile String lastPublishedTransactionId = null;

    /**
     * transactionId đang được thread Redis xử lý (kể cả đang retry), để thread đọc
     * tránh ghi đè trùng.
     */
    private volatile String currentlyPublishingTransactionId = null;

    public void run() throws Exception {
        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());

        running = true;

        commandExecutor.submit(modemInitializer::initialize)
                .get(30, TimeUnit.SECONDS);

        commandExecutor.submit(this::scanLoop);
        redisExecutor.submit(this::publishLoop);

        log.info("SMS reader runtime started (poll delay={}ms after each scan completes).",
                appConfig.getUnreadPollIntervalMs());
    }

    public void shutdown() {
        if (!running && commandExecutor.isShutdown() && redisExecutor.isShutdown()) {
            return;
        }

        log.info("Graceful shutdown requested...");
        running = false;

        if (pollFuture != null) {
            pollFuture.cancel(false);
        }

        // Đánh thức thread publish nếu đang wait() để nó thấy running=false và thoát.
        synchronized (publishLock) {
            publishLock.notifyAll();
        }

        commandExecutor.shutdown();
        redisExecutor.shutdown();

        try {
            if (!commandExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("commandExecutor did not terminate in time, forcing shutdown.");
                commandExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            commandExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            if (!redisExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("redisExecutor did not terminate in time, forcing shutdown.");
                redisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            redisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        portManager.close();
        log.info("Shutdown complete.");
    }

    private void scanLoop() {
        log.info("Scan loop started.");

        while (running) {
            try {
                doScanAndPublish();
            } catch (Exception e) {
                // doScanAndPublish tự xử lý exception bên trong,
                // catch ở đây chỉ để loop không bị dừng do lỗi bất ngờ
                log.error("Unexpected error in scan loop: {}", e.getMessage(), e);
            }

            // Nghỉ đúng interval SAU KHI scan xong
            // InterruptedException từ shutdownNow() → thoát loop ngay lập tức
            try {
                Thread.sleep(appConfig.getUnreadPollIntervalMs());
            } catch (InterruptedException e) {
                log.info("Scan loop interrupted, shutting down.");
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Scan loop stopped.");
    }

    private void doScanAndPublish() {
        try {
            List<SmsMessage> allMessages;
            try {
                allMessages = smsService.readAndParseAll();
            } catch (SerialPortException | ModemTimeoutException e) {
                log.error("Modem communication error during scan: {}. Attempting to reconnect...", e.getMessage());
                handleModemReconnect();
                return;
            } catch (Exception e) {
                log.error("Failed to read/parse SMS from SIM: {}", e.getMessage(), e);
                return;
            }

            if (allMessages.isEmpty()) {
                log.debug("No OTP SMS found on SIM.");
                return;
            }

            SmsMessage latest = allMessages.get(allMessages.size() - 1);
            log.info("Scan found {} OTP SMS, latest: index={} transactionId={} timestamp={}.",
                    allMessages.size(), latest.getIndex(), latest.getTransactionId(), latest.getTimestamp());

            String latestId = latest.getTransactionId();
            if (Objects.equals(latestId, lastPublishedTransactionId)) {
                log.debug("SMS transactionId={} already published. Skipping.", latestId);
            } else if (Objects.equals(latestId, currentlyPublishingTransactionId)) {
                log.debug("SMS transactionId={} is currently being published/retried. Skipping.", latestId);
            } else {
                telegramNotifier.notifyAsync(latest);
                offerForPublish(latest);
            }

            if (appConfig.isDeleteSmsAfterRead()) {
                for (SmsMessage msg : allMessages) {
                    smsService.deleteSms(msg.getIndex());
                }
            }

            try {
                smsService.cleanupOldSms(allMessages);
            } catch (Exception e) {
                log.warn("SIM cleanup failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Unexpected error during SMS scan: {}", e.getMessage(), e);
        } finally {
            scanInProgress.set(false);
        }
    }

    /**
     * Đưa tin nhắn vào biến chờ (pendingMessage) và báo hiệu cho thread Redis.
     * Nếu thread Redis chưa kịp lấy tin trước đó (hiếm khi xảy ra), tin cũ trong
     * biến sẽ bị ghi đè bởi tin mới nhất — vì ta luôn chỉ quan tâm tin mới nhất.
     */
    private void offerForPublish(SmsMessage msg) {
        synchronized (publishLock) {
            pendingMessage = msg;
            publishLock.notifyAll();
        }
    }

    /**
     * Thực hiện kết nối lại cổng serial và khởi tạo lại modem khi gặp sự cố phần
     * cứng.
     */
    private void handleModemReconnect() {
        try {
            portManager.reconnect();
            modemInitializer.initialize();
            log.info("Modem reconnected and reinitialized successfully.");
        } catch (Exception e) {
            log.error("Failed to reconnect/reinitialize modem: {}", e.getMessage(), e);
        }
    }

    /**
     * Loop chạy trên redisExecutor thread: chờ (wait) tin mới trong pendingMessage,
     * lấy ra rồi publish. Không busy-loop — chỉ thức khi có notify từ scan thread
     * hoặc khi shutdown() gọi notifyAll().
     */
    private void publishLoop() {
        log.info("Redis publish loop started.");

        while (true) {
            SmsMessage msgToPublish;

            synchronized (publishLock) {
                while (pendingMessage == null && running) {
                    try {
                        publishLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.info("Redis publish loop interrupted, shutting down.");
                        return;
                    }
                }

                if (pendingMessage == null) {
                    // running == false và không còn gì để publish → thoát.
                    break;
                }

                msgToPublish = pendingMessage;
                pendingMessage = null;
            }

            publishWithRetry(msgToPublish);
        }

        log.info("Redis publish loop stopped.");
    }

    /**
     * Publish SMS lên Redis với exponential backoff retry.
     * Chạy trên redisExecutor thread (KHÔNG còn ảnh hưởng tới việc poll modem).
     *
     * Retry tối đa 3 lần: backoff 1s → 2s → dừng.
     * Nếu tất cả lần thử fail → log error, KHÔNG update lastPublishedTransactionId
     * → scan tiếp theo sẽ tự động thử lại (vì transactionId vẫn khác
     * lastPublishedTransactionId
     * và currentlyPublishingTransactionId sẽ được clear ở finally).
     */
    private void publishWithRetry(SmsMessage msg) {
        currentlyPublishingTransactionId = msg.getTransactionId();
        try {
            int maxRetries = 3;
            long backoffMs = 1000L;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                if (!running) {
                    log.info("Publish aborted — runtime is shutting down.");
                    return;
                }

                try {
                    redisPublisher.publish(msg);

                    lastPublishedTransactionId = msg.getTransactionId();
                    log.info("Published SMS transactionId={} index={} (attempt {}/{}).",
                            msg.getTransactionId(), msg.getIndex(), attempt, maxRetries);

                    return;

                } catch (Exception e) {
                    log.warn("Publish attempt {}/{} failed for transactionId={}: {}",
                            attempt, maxRetries, msg.getTransactionId(), e.getMessage());

                    if (attempt < maxRetries && running) {
                        try {
                            Thread.sleep(backoffMs);
                            backoffMs *= 2; // 1s → 2s
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            // Tất cả retry fail — log error, để scan tiếp theo retry tự nhiên
            log.error("Failed to publish SMS transactionId={} index={} after {} attempts. Will retry on next scan.",
                    msg.getTransactionId(), msg.getIndex(), maxRetries);
        } finally {
            currentlyPublishingTransactionId = null;
        }
    }
}