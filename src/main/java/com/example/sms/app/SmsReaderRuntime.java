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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

public class SmsReaderRuntime {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);

    private final SerialPortManager portManager;
    private final ModemInitializer modemInitializer;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;
    private final TelegramNotifier telegramNotifier;
    private final AppConfig appConfig;

    private volatile String lastRedisTransactionId = null;
    private volatile String lastTelegramTransactionId = null;

    private final ExecutorService scanExecutor = newNamedSingle("sms-serial-scanner");
    private final ExecutorService redisExecutor = newNamedSingle("sms-redis-publisher");
    private final ExecutorService telegramExecutor = newNamedSingle("sms-telegram-notifier");
    private final ExecutorService deleteExecutor = newNamedSingle("sms-cleaner");

    private final Scheduler scanScheduler = Schedulers.from(scanExecutor);
    private final Scheduler redisScheduler = Schedulers.from(redisExecutor);
    private final Scheduler telegramScheduler = Schedulers.from(telegramExecutor);
    private final Scheduler deleteScheduler = Schedulers.from(deleteExecutor);

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Subject<ScanResult> scanResults = PublishSubject.<ScanResult>create().toSerialized();
    private final Object modemLock;

    public SmsReaderRuntime(
            SerialPortManager portManager,
            ModemInitializer modemInitializer,
            SmsService smsService,
            RedisPublisher redisPublisher,
            TelegramNotifier telegramNotifier,
            AppConfig appConfig) {
        this(portManager, modemInitializer, smsService, redisPublisher, telegramNotifier, appConfig, new Object());
    }

    public SmsReaderRuntime(
            SerialPortManager portManager,
            ModemInitializer modemInitializer,
            SmsService smsService,
            RedisPublisher redisPublisher,
            TelegramNotifier telegramNotifier,
            AppConfig appConfig,
            Object modemLock) {
        this.portManager = portManager;
        this.modemInitializer = modemInitializer;
        this.smsService = smsService;
        this.redisPublisher = redisPublisher;
        this.telegramNotifier = telegramNotifier;
        this.appConfig = appConfig;
        this.modemLock = modemLock != null ? modemLock : new Object();
    }

    private static final long INITIAL_MODEM_RECONNECT_BACKOFF_MS = 5_000L;
    private static final long MAX_MODEM_RECONNECT_BACKOFF_MS = 60_000L;
    private long modemReconnectBackoffMs = INITIAL_MODEM_RECONNECT_BACKOFF_MS;
    private long nextModemReconnectAtMs = 0L;

    private volatile boolean APPLICATION_RUNNING = false;

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    public void run() throws Exception {
        connectModerm();
        registerSubscribers();
        startScanLoop();
        log.info("Bộ chạy đọc SMS đã khởi động (chu kỳ quét={}ms).", appConfig.getUnreadPollIntervalMs());
    }

    public void connectModerm() throws InterruptedException, ExecutionException, TimeoutException {
        log.info("Các cổng serial hiện có: {}", SerialPortManager.listAvailablePorts());
        APPLICATION_RUNNING = true;
        scanExecutor.submit(() -> {
            synchronized (modemLock) {
                modemInitializer.initialize();
            }
        }).get(30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (!APPLICATION_RUNNING)
            return;
        log.info("Đã nhận yêu cầu tắt ứng dụng an toàn...");
        APPLICATION_RUNNING = false;

        disposables.dispose();
        shutdownExecutor(scanExecutor, "sms-scan");
        shutdownExecutor(redisExecutor, "sms-redis");
        shutdownExecutor(telegramExecutor, "sms-telegram");
        shutdownExecutor(deleteExecutor, "sms-delete");

        synchronized (modemLock) {
            portManager.close();
        }
        log.info("Đã tắt bộ chạy hoàn tất.");
    }

    private void registerSubscribers() {
        log.info("Bộ lắng nghe Redis đã khởi động.");
        disposables.add(scanResults
                .observeOn(redisScheduler)
                .concatMap(scanResult -> Observable
                        .fromCallable(() -> {
                            redisPublish(scanResult);
                            return scanResult;
                        })
                        .retryWhen(retryRedisWithBackoff(scanResult.latest()))
                        .onErrorResumeNext(e -> {
                            log.error(
                                    "Gửi Redis thất bại cho transactionId={} index={} sau {} lần thử. Sẽ thử lại ở lần quét tiếp theo.",
                                    scanResult.latest().getTransactionId(),
                                    scanResult.latest().getIndex(),
                                    Math.max(1, appConfig.getRedisPublishRetries()));
                            return Observable.empty();
                        }))
                .subscribe(
                        ignored -> {
                        },
                        e -> log.error("Bộ lắng nghe Redis dừng do lỗi RxJava ngoài dự kiến: {}", e.getMessage(), e)));
        log.info("Bộ lắng nghe Telegram đã khởi động.");
        disposables.add(scanResults
                .observeOn(telegramScheduler)
                .subscribe(
                        this::telegramSend,
                        e -> log.error("Bộ lắng nghe Telegram dừng do lỗi RxJava ngoài dự kiến: {}", e.getMessage(),
                                e)));
        log.info("Bộ lắng nghe dọn dẹp SIM đã khởi động.");
        disposables.add(scanResults
                .map(ScanResult::allMessages)
                .observeOn(deleteScheduler)
                .subscribe(
                        this::removeOldSMS,
                        e -> log.error("Bộ lắng nghe dọn dẹp SIM dừng do lỗi RxJava ngoài dự kiến: {}", e.getMessage(),
                                e)));
    }

    private void startScanLoop() {
        log.info("Vòng quét SMS đã khởi động.");
        disposables
                .add(Observable.interval(0L, appConfig.getUnreadPollIntervalMs(), TimeUnit.MILLISECONDS, scanScheduler)
                        .subscribe(
                                ignored -> scanModermToGetSMS(),
                                e -> log.error("Vòng quét SMS dừng do lỗi RxJava ngoài dự kiến: {}", e.getMessage(), e)));
    }

    private void scanModermToGetSMS() {
        if (!APPLICATION_RUNNING) {
            return;
        }
        List<SmsMessage> allMessages;
        try {
            synchronized (modemLock) {
                allMessages = smsService.readAndParseAll();
            }
        } catch (SerialPortException | ModemTimeoutException e) {
            log.error("Lỗi giao tiếp với modem: {}. Đang kiểm tra kết nối lại...", e.getMessage());
            handleModemReconnect();
            return;
        } catch (Exception e) {
            log.error("Không thể đọc/phân tích SMS: {}", e.getMessage(), e);
            return;
        }

        if (allMessages.isEmpty()) {
            log.debug("Không tìm thấy SMS OTP nào trên SIM.");
            return;
        }

        if (!APPLICATION_RUNNING) {
            return;
        }

        SmsMessage latest = allMessages.get(allMessages.size() - 1);
        String latestId = latest.getTransactionId();

        log.info("Quét thấy {} SMS OTP, thông tin SMS mới nhất đã gửi đi: latest.index={} latest.transactionId={} latest.timestamp={}.",
                allMessages.size(), latest.getIndex(), latestId, latest.getTimestamp());

        scanResults.onNext(new ScanResult(latest, new ArrayList<>(allMessages)));
    }

    private void redisPublish(ScanResult scanResult) {
        if (scanResult == null) {
            return;
        }
        SmsMessage msg = scanResult.latest();
        String transactionId = msg.getTransactionId();
        if (Objects.equals(transactionId, lastRedisTransactionId)) {
            log.debug("Redis đã xử lý transactionId={}. Bỏ qua.", transactionId);
            return;
        }
        redisPublisher.publish(msg);
        lastRedisTransactionId = msg.getTransactionId();
        log.info("Đã gửi Redis transactionId={} index={}.", msg.getTransactionId(), msg.getIndex());
    }

    private Function<Observable<Throwable>, Observable<?>> retryRedisWithBackoff(SmsMessage msg) {
        int maxAttempts = Math.max(1, appConfig.getRedisPublishRetries());
        return errors -> errors
                .zipWith(Observable.range(1, maxAttempts),
                        (error, attempt) -> new RedisRetryAttempt(error, attempt, maxAttempts))
                .flatMap(retry -> {
                    log.warn("Lần gửi Redis {}/{} thất bại cho transactionId={}: {}",
                            retry.attempt, retry.maxAttempts, msg.getTransactionId(), retry.error.getMessage());
                    if (!APPLICATION_RUNNING && retry.attempt > 1) {
                        log.info("Hủy gửi Redis vì bộ chạy đang tắt.");
                        return Observable.error(retry.error);
                    }
                    if (retry.attempt >= retry.maxAttempts) {
                        return Observable.error(retry.error);
                    }
                    return Observable.timer(redisBackoffMs(retry.attempt), TimeUnit.MILLISECONDS);
                });
    }

    private long redisBackoffMs(int failedAttempt) {
        return 1000L << Math.max(0, failedAttempt - 1);
    }

    // ========================================================================
    // TELEGRAM LOOP
    // ========================================================================

    private void telegramSend(ScanResult scanResult) {
        if (scanResult == null) {
            return;
        }
        SmsMessage msg = scanResult.latest();
        String transactionId = msg.getTransactionId();
        try {
            if (Objects.equals(transactionId, lastTelegramTransactionId)) {
                log.debug("Telegram đã xử lý transactionId={}. Bỏ qua.", transactionId);
                return;
            }
            telegramNotifier.sendSync(msg);
            lastTelegramTransactionId = msg.getTransactionId();
            log.info("Đã gửi thông báo Telegram cho transactionId={} index={}.",
                    msg.getTransactionId(), msg.getIndex());
        } catch (Exception e) {
            log.warn("Gửi thông báo Telegram thất bại cho transactionId={} index={}: {}",
                    msg.getTransactionId(), msg.getIndex(), e.getMessage());
        }
    }

    // ========================================================================
    // CLEANUP LOOP
    // ========================================================================

    private void removeOldSMS(List<SmsMessage> batch) {
        synchronized (modemLock) {
            if (appConfig.isDeleteSmsAfterRead()) {
                for (SmsMessage msg : batch) {
                    try {
                        smsService.deleteSms(msg.getIndex());
                        log.debug("Đã xóa SMS index={}.", msg.getIndex());
                    } catch (Exception e) {
                        log.warn("Không thể xóa SMS index={}: {}", msg.getIndex(), e.getMessage());
                    }
                }
            }
            try {
                smsService.cleanupOldSms(batch);
            } catch (Exception e) {
                log.warn("Dọn dẹp SIM thất bại: {}", e.getMessage());
            }
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void handleModemReconnect() {
        synchronized (modemLock) {
            if (!APPLICATION_RUNNING) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now < nextModemReconnectAtMs) {
                log.warn("Bỏ qua kết nối lại modem; lần thử tiếp theo sau {}ms.",
                        nextModemReconnectAtMs - now);
                return;
            }

            try {
                portManager.reconnect();
                modemInitializer.initialize();
                modemReconnectBackoffMs = INITIAL_MODEM_RECONNECT_BACKOFF_MS;
                nextModemReconnectAtMs = 0L;
                log.info("Modem đã kết nối lại và khởi tạo lại thành công.");
            } catch (Exception e) {
                nextModemReconnectAtMs = now + modemReconnectBackoffMs;
                log.error("Không thể kết nối lại/khởi tạo lại modem: {}", e.getMessage(), e);
                modemReconnectBackoffMs = Math.min(
                        modemReconnectBackoffMs * 2,
                        MAX_MODEM_RECONNECT_BACKOFF_MS);
            }
        }
    }

    private void shutdownExecutor(ExecutorService ex, String name) {
        ex.shutdown();
        try {
            if (!ex.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("{} không dừng kịp thời, buộc tắt bộ thực thi.", name);
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newNamedSingle(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(false);
            return t;
        });
    }

    private static final class RedisRetryAttempt {
        private final Throwable error;
        private final int attempt;
        private final int maxAttempts;

        private RedisRetryAttempt(Throwable error, int attempt, int maxAttempts) {
            this.error = error;
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
        }
    }

    @Data
    private static final class ScanResult {
        private final SmsMessage latest;
        private final List<SmsMessage> allMessages;

        private ScanResult(SmsMessage latest, List<SmsMessage> allMessages) {
            this.latest = latest;
            this.allMessages = allMessages;
        }

        private SmsMessage latest() {
            return latest;
        }

        private List<SmsMessage> allMessages() {
            return allMessages;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ScanResult that = (ScanResult) o;
            return Objects.equals(latest, that.latest)
                    && Objects.equals(allMessages, that.allMessages);
        }

        @Override
        public int hashCode() {
            return Objects.hash(latest, allMessages);
        }
    }
}
