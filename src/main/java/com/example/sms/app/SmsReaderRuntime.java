package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;
import com.example.sms.telegram.TelegramNotifier;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime chính chịu trách nhiệm điều phối toàn bộ luồng xử lý SMS.
 *
 * Chức năng chính:
 * - Khởi tạo modem
 * - Scan toàn bộ SMS trên SIM định kỳ (AT+CMGL="ALL")
 * - Đọc và parse SMS mới nhất
 * - Publish message sang Redis
 * - Gửi Telegram notification
 *
 * Thiết kế concurrency:
 * - 1 single-thread executor để serialize AT command (commandExecutor)
 * - 1 scheduler scan định kỳ
 * - AtomicBoolean chống overlap scan
 */
@RequiredArgsConstructor
public class SmsReaderRuntime {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);

    private final SerialPortManager portManager;
    private final ModemInitializer modemInitializer;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;
    private final TelegramNotifier telegramNotifier;
    private final ScheduledExecutorService scheduler;
    private final AppConfig appConfig;

    /**
     * Executor chạy tuần tự toàn bộ modem command.
     *
     * Mục đích:
     * - Tránh race condition khi gửi AT command
     * - Đảm bảo response modem đúng thứ tự
     * - Tránh modem bị deadlock / corrupted buffer
     */
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-command-thread");
        t.setDaemon(true);
        return t;
    });

    /**
     * Flag điều khiển lifecycle runtime.
     */
    private volatile boolean running = false;

    /**
     * Task scan SMS định kỳ (lấy tin nhắn mới nhất từ toàn bộ SMS trên SIM).
     */
    private ScheduledFuture<?> latestSmsPollFuture;

    /**
     * Chống overlap nhiều lần scan cùng lúc.
     *
     * Ví dụ:
     * - Scan cũ chưa xong
     * - Scheduler trigger scan mới
     *
     * => bỏ qua để tránh queue bị dồn.
     */
    private final AtomicBoolean latestSmsPollInProgress = new AtomicBoolean(false);

    /**
     * Entry point khi application start.
     *
     * Flow startup:
     * 1. Initialize modem
     * 2. Start scheduler scan định kỳ (lấy tin nhắn mới nhất từ ALL SMS)
     */
    public void run() throws Exception {

        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());

        running = true;

        /**
         * Initialize modem đồng bộ.
         *
         * Nếu modem init fail thì application fail fast luôn.
         */
        commandExecutor.submit(modemInitializer::initialize)
                .get(30, TimeUnit.SECONDS);

        /**
         * Scheduler scan SMS định kỳ.
         *
         * Đọc toàn bộ SMS trên SIM (AT+CMGL="ALL")
         * Lấy tin nhắn mới nhất (theo timestamp) để process
         * Tránh miss SMS do mất event hoặc process restart
         */
        latestSmsPollFuture = scheduler.scheduleAtFixedRate(
                this::scheduleLatestSmsPoll,
                1000L,
                appConfig.getUnreadPollIntervalMs(),
                TimeUnit.MILLISECONDS);

        log.info("SMS reader runtime started (scheduled scan every {}ms).",
                appConfig.getUnreadPollIntervalMs());
    }

    /**
     * Schedule scan SMS an toàn, chống overlap nhiều task scan cùng lúc.
     *
     * Thực hiện AT+CMGL="ALL", lấy index mới nhất và xử lý.
     */
    private void scheduleLatestSmsPoll() {

        if (!running) {
            return;
        }

        /**
         * Nếu đang có scan chạy thì skip.
         */
        if (!latestSmsPollInProgress.compareAndSet(false, true)) {

            log.debug("Skipping SMS scan schedule because the previous run is still queued/running.");

            return;
        }

        try {

            /**
             * Submit scan vào command executor
             * để serialize modem access.
             */
            commandExecutor.submit(this::processScheduledLatestSms);

        } catch (Exception e) {

            latestSmsPollInProgress.set(false);

            log.warn(
                    "Could not submit SMS scan schedule: {}",
                    e.getMessage());
        }
    }

    /**
     * Scan toàn bộ SMS trên SIM, sắp xếp theo timestamp thực tế, và chỉ publish
     * SMS mới hơn state hiện tại trong Redis (tránh duplicate).
     *
     * <p>
     * KHÔNG dùng «index lớn nhất = tin mới nhất» vì modem tái sử dụng slot
     * sau khi xóa: index thấp có thể là tin mới hơn. Timestamp từ nội dung SMS
     * là nguồn sự thật về thứ tự thời gian.
     * </p>
     *
     * <p>
     * Xử lý toàn bộ tin OTP hiện có trên SIM (không chỉ 1 tin) để
     * tránh miss SMS bất kể việc phân bổ index như thế nào.
     * </p>
     */
    private void processScheduledLatestSms() {

        try {

            List<SmsMessage> allMessages;
            try {
                allMessages = smsService.readAndParseAll();
            } catch (Exception e) {
                log.error("Scheduled SMS scan failed to read/parse all SMS: {}",
                        e.getMessage(), e);
                return;
            }

            if (allMessages.isEmpty()) {

                log.debug("Scheduled SMS scan: no OTP messages found on SIM.");

            } else {

                // Danh sách đã sắp xếp timestamp tăng dần;
                // chỉ cần publish tin mới nhất (cuối list) qua conditional check.
                SmsMessage latestMsg = allMessages.get(allMessages.size() - 1);
                log.info("Scheduled SMS scan: {} OTP SMS found, latest is index={} timestamp={}.",
                        allMessages.size(), latestMsg.getIndex(), latestMsg.getTimestamp());

                try {

                    /**
                     * Chỉ publish nếu SMS mới hơn current state.
                     *
                     * Mục đích:
                     * - Tránh duplicate message
                     * - Tránh republish SMS cũ
                     */
                    boolean published = redisPublisher.publishIfNewerThanCurrent(latestMsg);

                    // Gửi Telegram notify nếu SMS thực sự được publish (mới hơn state hiện tại)
                    if (published) {
                        telegramNotifier.notifyAsync(latestMsg);
                    }

                } catch (Exception e) {

                    log.error(
                            "Conditional Redis publish failed for scheduled SMS index={}: {}",
                            latestMsg.getIndex(),
                            e.getMessage(),
                            e);
                }

                // Xóa các SMS đã được xử lý nếu cấu hình bật DELETE_SMS_AFTER_READ
                if (appConfig.isDeleteSmsAfterRead()) {
                    for (SmsMessage msg : allMessages) {
                        smsService.deleteSms(msg.getIndex());
                    }
                }
            }

            // Cleanup SIM nếu vượt ngưỡng watermark — dùng list đã đọc sẵn,
            // không cần đọc lại SIM lần thứ hai.
            try {
                smsService.cleanupOldSms(allMessages);
            } catch (Exception e) {
                log.warn("SIM cleanup failed: {}", e.getMessage());
            }

        } catch (Exception e) {

            log.error(
                    "Scheduled SMS scan failed: {}",
                    e.getMessage(),
                    e);

        } finally {

            /**
             * Luôn release lock dù success/fail.
             */
            latestSmsPollInProgress.set(false);
        }
    }

    /**
     * Shutdown application một cách an toàn (graceful shutdown).
     *
     * Thứ tự shutdown:
     * 1. Dừng flag running
     * 2. Huỷ scheduler scan SMS
     * 3. Shutdown command executor
     * 4. Đóng serial port và giải phóng resource
     */
    public void shutdown() {

        /**
         * Tránh shutdown nhiều lần.
         */
        if (!running && commandExecutor.isShutdown()) {
            return;
        }

        log.info("Graceful shutdown requested...");

        running = false;

        // Cancel scheduler task
        if (latestSmsPollFuture != null) {
            latestSmsPollFuture.cancel(false);
        }

        // Graceful shutdown executor
        commandExecutor.shutdown();

        try {

            /**
             * Chờ task đang chạy finish.
             */
            if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {

                /**
                 * Force shutdown nếu timeout.
                 */
                commandExecutor.shutdownNow();
            }

        } catch (InterruptedException e) {

            commandExecutor.shutdownNow();

            Thread.currentThread().interrupt();
        }

        // Release serial resources
        portManager.close();

        log.info("Shutdown complete.");
    }
}