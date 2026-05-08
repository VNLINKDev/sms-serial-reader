package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.modem.SmsIndexDetector;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.serial.SerialReaderService;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runtime chính chịu trách nhiệm điều phối toàn bộ luồng xử lý SMS.
 *
 * Chức năng chính:
 * - Khởi tạo modem
 * - Theo dõi SMS mới liên tục
 * - Đọc và parse SMS
 * - Publish message sang Redis
 * - Scan SMS unread định kỳ để tránh miss message
 *
 * Thiết kế concurrency:
 * - 1 thread polling liên tục detect SMS mới
 * - 1 single-thread executor để serialize AT command
 * - 1 scheduler scan SMS unread định kỳ
 */
@Component
@RequiredArgsConstructor
public class SmsReaderRuntime implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);

    /**
     * Thời gian sleep giữa các lần polling modem.
     */
    private static final int POLL_INTERVAL_MS = 100;

    private final SerialPortManager portManager;
    private final SerialReaderService readerService;
    private final ModemInitializer modemInitializer;
    private final SmsIndexDetector indexDetector;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;
    private final TaskScheduler taskScheduler;
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
     * Thread polling chính dùng để detect SMS mới.
     */
    private Thread pollThread;

    /**
     * Task scan unread SMS định kỳ.
     */
    private ScheduledFuture<?> unreadPollFuture;

    /**
     * Chống overlap nhiều lần scan unread SMS.
     *
     * Ví dụ:
     * - Scan cũ chưa xong
     * - Scheduler trigger scan mới
     *
     * => bỏ qua để tránh queue bị dồn.
     */
    private final AtomicBoolean unreadPollInProgress = new AtomicBoolean(false);

    /**
     * Entry point khi application start.
     *
     * Flow startup:
     * 1. Start serial reader
     * 2. Initialize modem
     * 3. Start polling SMS mới
     * 4. Start scheduler scan unread SMS
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {

        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());

        running = true;

        // Start reader đọc raw data từ serial port
        readerService.start();

        /**
         * Initialize modem đồng bộ.
         *
         * Nếu modem init fail thì application fail fast luôn.
         */
        commandExecutor.submit(modemInitializer::initialize)
                .get(30, TimeUnit.SECONDS);

        // Start polling thread detect SMS mới
        pollThread = new Thread(this::pollLoop, "sms-poll-thread");
        pollThread.setDaemon(true);
        pollThread.start();

        /**
         * Scheduler scan SMS unread định kỳ.
         *
         * Đây là cơ chế fallback:
         * - Tránh miss SMS do mất event modem
         * - Recover SMS chưa xử lý
         */
        unreadPollFuture = taskScheduler.scheduleAtFixedRate(
                this::scheduleUnreadPoll,
                Duration.ofMillis(appConfig.getUnreadPollIntervalMs()));

        log.info("SMS reader runtime started.");
    }

    /**
     * Polling loop chính.
     *
     * Liên tục detect index SMS mới từ modem.
     */
    private void pollLoop() {

        log.info("Entering main poll loop.");

        while (running) {

            /**
             * Detect các SMS index mới.
             *
             * Ví dụ modem trả:
             * +CMTI: "SM",12
             *
             * => detect ra index = 12
             */
            List<Integer> newIndexes = indexDetector.detect();

            for (int index : newIndexes) {

                /**
                 * Submit vào single-thread executor
                 * để đảm bảo modem command chạy tuần tự.
                 */
                commandExecutor.submit(() -> processIncomingSms(index));
            }

            try {

                Thread.sleep(POLL_INTERVAL_MS);

            } catch (InterruptedException e) {

                /**
                 * Preserve interrupt flag
                 * và shutdown loop gracefully.
                 */
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.debug("Poll loop exiting.");
    }

    /**
     * Xử lý SMS mới nhận được.
     *
     * Flow:
     * 1. Read SMS từ modem
     * 2. Parse SMS
     * 3. Publish Redis
     */
    private void processIncomingSms(int index) {

        Optional<SmsMessage> msgOpt = smsService.readAndParse(index);

        msgOpt.ifPresent(msg -> {
            try {

                // Publish realtime sang Redis
                redisPublisher.publish(msg);

            } catch (Exception e) {

                /**
                 * Không để lỗi Redis làm crash runtime.
                 */
                log.error(
                        "Redis publish failed for SMS index={}: {}",
                        index,
                        e.getMessage(),
                        e
                );
            }
        });
    }

    /**
     * Schedule unread SMS scan an toàn.
     *
     * Chống overlap nhiều task scan cùng lúc.
     */
    private void scheduleUnreadPoll() {

        if (!running) {
            return;
        }

        /**
         * Nếu đang có scan chạy thì skip.
         */
        if (!unreadPollInProgress.compareAndSet(false, true)) {

            log.info(
                    "Skipping unread SMS schedule because the previous run is still queued/running."
            );

            return;
        }

        try {

            /**
             * Submit scan vào command executor
             * để serialize modem access.
             */
            commandExecutor.submit(this::processScheduledUnreadSms);

        } catch (Exception e) {

            unreadPollInProgress.set(false);

            log.warn(
                    "Could not submit unread SMS schedule: {}",
                    e.getMessage()
            );
        }
    }

    /**
     * Scan SMS unread định kỳ.
     *
     * Đây là cơ chế recovery chống miss SMS.
     */
    private void processScheduledUnreadSms() {

        try {

            List<Integer> unreadIndexes = smsService.listUnreadIndexes();

            if (unreadIndexes.isEmpty()) {

                log.info("Scheduled unread SMS scan found no messages.");
                return;
            }

            log.info(
                    "Scheduled unread SMS scan found indexes: {}",
                    unreadIndexes
            );

            for (int index : unreadIndexes) {

                Optional<SmsMessage> msgOpt = smsService.readAndParse(index);

                msgOpt.ifPresent(msg -> {
                    try {

                        /**
                         * Chỉ publish nếu SMS mới hơn current state.
                         *
                         * Mục đích:
                         * - Tránh duplicate message
                         * - Tránh republish SMS cũ
                         */
                        redisPublisher.publishIfNewerThanCurrent(msg);

                    } catch (Exception e) {

                        log.error(
                                "Conditional Redis publish failed for scheduled SMS index={}: {}",
                                index,
                                e.getMessage(),
                                e
                        );
                    }
                });
            }

        } catch (Exception e) {

            log.error(
                    "Scheduled unread SMS scan failed: {}",
                    e.getMessage(),
                    e
            );

        } finally {

            /**
             * Luôn release lock dù success/fail.
             */
            unreadPollInProgress.set(false);
        }
    }

    /**
     * Shutdown application một cách an toàn (graceful shutdown).
     *
     * Thứ tự shutdown:
     * 1. Dừng polling loop
     * 2. Huỷ scheduler scan unread SMS
     * 3. Shutdown command executor
     * 4. Stop serial reader service
     * 5. Đóng serial port và giải phóng resource
     */
    @PreDestroy
    public void shutdown() {

        /**
         * Tránh shutdown nhiều lần.
         */
        if (!running && commandExecutor.isShutdown()) {
            return;
        }

        log.info("Graceful shutdown requested...");

        running = false;

        // Interrupt polling thread
        if (pollThread != null) {
            pollThread.interrupt();
        }

        // Cancel scheduler task
        if (unreadPollFuture != null) {
            unreadPollFuture.cancel(false);
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
        readerService.stop();
        portManager.close();

        log.info("Shutdown complete.");
    }
}