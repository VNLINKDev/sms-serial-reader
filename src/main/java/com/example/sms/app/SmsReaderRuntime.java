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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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

    private final AtomicReference<SmsMessage> pendingRedis = new AtomicReference<>();
    private final AtomicReference<SmsMessage> pendingTelegram = new AtomicReference<>();
    private final AtomicReference<List<SmsMessage>> pendingDelete = new AtomicReference<>();

    private volatile Thread redisThread;
    private volatile Thread telegramThread;
    private volatile Thread deleteThread;

    private volatile String lastRedisTransactionId = null;
    private volatile String lastTelegramTransactionId = null;

    private final ExecutorService scanExecutor = newDaemonSingle("sms-scan");
    private final ExecutorService redisExecutor = newDaemonSingle("sms-redis");
    private final ExecutorService telegramExecutor = newDaemonSingle("sms-telegram");
    private final ExecutorService deleteExecutor = newDaemonSingle("sms-delete");

    private volatile boolean running = false;

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    public void run() throws Exception {
        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());
        running = true;

        scanExecutor.submit(modemInitializer::initialize)
                .get(30, TimeUnit.SECONDS);

        redisExecutor.submit(this::redisLoop);
        telegramExecutor.submit(this::telegramLoop);
        deleteExecutor.submit(this::deleteLoop);

        while (redisThread == null || telegramThread == null || deleteThread == null) {
            Thread.onSpinWait();
        }

        scanExecutor.submit(this::scanLoop);

        log.info("SMS reader runtime started (poll interval={}ms).",
                appConfig.getUnreadPollIntervalMs());
    }

    public void shutdown() {
        if (!running)
            return;
        log.info("Graceful shutdown requested...");
        running = false;

        // Đánh thức tất cả consumer đang park() để chúng kiểm tra running=false và
        // thoát
        if (redisThread != null)
            LockSupport.unpark(redisThread);
        if (telegramThread != null)
            LockSupport.unpark(telegramThread);
        if (deleteThread != null)
            LockSupport.unpark(deleteThread);

        shutdownExecutor(scanExecutor, "sms-scan");
        shutdownExecutor(redisExecutor, "sms-redis");
        shutdownExecutor(telegramExecutor, "sms-telegram");
        shutdownExecutor(deleteExecutor, "sms-delete");

        portManager.close();
        log.info("Shutdown complete.");
    }

    private void scanLoop() {
        log.info("Scan loop started.");

        while (running) {
            try {
                doScan();
            } catch (Exception e) {
                log.error("Unexpected error in scan loop: {}", e.getMessage(), e);
            }

            try {
                Thread.sleep(appConfig.getUnreadPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Scan loop stopped.");
    }

    private void doScan() {
        List<SmsMessage> allMessages;
        try {
            allMessages = smsService.readAndParseAll();
        } catch (SerialPortException | ModemTimeoutException e) {
            log.error("Modem communication error: {}. Attempting to reconnect...", e.getMessage());
            handleModemReconnect();
            return;
        } catch (Exception e) {
            log.error("Failed to read/parse SMS: {}", e.getMessage(), e);
            return;
        }

        if (allMessages.isEmpty()) {
            log.debug("No OTP SMS found on SIM.");
            return;
        }

        SmsMessage latest = allMessages.get(allMessages.size() - 1);
        String latestId = latest.getTransactionId();

        log.info("Scan found {} OTP SMS, latest: index={} transactionId={} timestamp={}.",
                allMessages.size(), latest.getIndex(), latestId, latest.getTimestamp());

        // Kiểm tra từng consumer độc lập — một consumer đã xử lý không ảnh hưởng
        // consumer kia
        boolean needRedis = !Objects.equals(latestId, lastRedisTransactionId);
        boolean needTelegram = !Objects.equals(latestId, lastTelegramTransactionId);

        if (needRedis) {
            // set() ghi đè nếu consumer chưa kịp lấy — "latest-wins"
            pendingRedis.set(latest);
            LockSupport.unpark(redisThread);
        } else {
            log.debug("Redis already handled transactionId={}. Skipping.", latestId);
        }

        if (needTelegram) {
            pendingTelegram.set(latest);
            LockSupport.unpark(telegramThread);
        } else {
            log.debug("Telegram already handled transactionId={}. Skipping.", latestId);
        }

        // Delete luôn nhận full list, không cần track last
        if (appConfig.isDeleteSmsAfterRead()) {
            pendingDelete.set(new ArrayList<>(allMessages));
            LockSupport.unpark(deleteThread);
        }
    }

    private void redisLoop() {
        redisThread = Thread.currentThread();
        log.info("Redis loop started.");

        while (running) {
            LockSupport.park(this);

            SmsMessage msg = pendingRedis.getAndSet(null);
            if (msg == null)
                continue;

            publishWithRetry(msg);
        }

        SmsMessage msg = pendingRedis.getAndSet(null);
        if (msg != null)
            publishWithRetry(msg);

        log.info("Redis loop stopped.");
    }

    private void publishWithRetry(SmsMessage msg) {
        int maxRetries = 3;
        long backoffMs = 1000L;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (!running && attempt > 1) {
                log.info("Publish aborted — runtime is shutting down.");
                return;
            }

            try {
                redisPublisher.publish(msg);

                // Chỉ update last của redis — không ảnh hưởng telegram
                lastRedisTransactionId = msg.getTransactionId();
                log.info("Published to Redis transactionId={} index={} (attempt {}/{}).",
                        msg.getTransactionId(), msg.getIndex(), attempt, maxRetries);
                return;

            } catch (Exception e) {
                log.warn("Redis publish attempt {}/{} failed for transactionId={}: {}",
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

        // Tất cả retry fail — lastRedisTransactionId KHÔNG được update
        // → scan tiếp theo sẽ thấy needRedis=true và retry tự nhiên
        log.error("Failed to publish to Redis transactionId={} index={} after {} attempts. Will retry on next scan.",
                msg.getTransactionId(), msg.getIndex(), maxRetries);
    }

    // ========================================================================
    // TELEGRAM LOOP
    // ========================================================================

    private void telegramLoop() {
        telegramThread = Thread.currentThread();
        log.info("Telegram loop started.");

        while (running) {
            LockSupport.park(this);

            SmsMessage msg = pendingTelegram.getAndSet(null);
            if (msg == null)
                continue; // spurious wakeup

            sendTelegram(msg);
        }

        // Drain
        SmsMessage msg = pendingTelegram.getAndSet(null);
        if (msg != null)
            sendTelegram(msg);

        log.info("Telegram loop stopped.");
    }

    private void sendTelegram(SmsMessage msg) {
        try {
            telegramNotifier.sendSync(msg);

            lastTelegramTransactionId = msg.getTransactionId();
            log.info("Telegram notification sent for transactionId={} index={}.",
                    msg.getTransactionId(), msg.getIndex());

        } catch (Exception e) {
            // lastTelegramTransactionId KHÔNG được update
            // → scan tiếp theo sẽ thấy needTelegram=true và retry tự nhiên
            log.warn("Telegram notification failed for transactionId={} index={}: {}",
                    msg.getTransactionId(), msg.getIndex(), e.getMessage());
        }
    }

    // ========================================================================
    // DELETE LOOP
    // ========================================================================

    private void deleteLoop() {
        deleteThread = Thread.currentThread();
        log.info("Delete loop started.");

        while (running) {
            LockSupport.park(this);

            List<SmsMessage> batch = pendingDelete.getAndSet(null);
            if (batch == null)
                continue; // spurious wakeup

            deleteBatch(batch);
        }

        // Drain
        List<SmsMessage> batch = pendingDelete.getAndSet(null);
        if (batch != null)
            deleteBatch(batch);

        log.info("Delete loop stopped.");
    }

    private void deleteBatch(List<SmsMessage> batch) {
        for (SmsMessage msg : batch) {
            try {
                smsService.deleteSms(msg.getIndex());
                log.debug("Deleted SMS index={}.", msg.getIndex());
            } catch (Exception e) {
                log.warn("Failed to delete SMS index={}: {}", msg.getIndex(), e.getMessage());
            }
        }

        try {
            smsService.cleanupOldSms(batch);
        } catch (Exception e) {
            log.warn("SIM cleanup failed: {}", e.getMessage());
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void handleModemReconnect() {
        try {
            portManager.reconnect();
            modemInitializer.initialize();
            log.info("Modem reconnected and reinitialized successfully.");
        } catch (Exception e) {
            log.error("Failed to reconnect/reinitialize modem: {}", e.getMessage(), e);
        }
    }

    private void shutdownExecutor(ExecutorService ex, String name) {
        ex.shutdown();
        try {
            if (!ex.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate in time, forcing shutdown.", name);
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newDaemonSingle(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}