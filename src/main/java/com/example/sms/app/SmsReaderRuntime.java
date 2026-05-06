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

@Component
@RequiredArgsConstructor
public class SmsReaderRuntime implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);
    private static final int POLL_INTERVAL_MS = 100;

    private final SerialPortManager portManager;
    private final SerialReaderService readerService;
    private final ModemInitializer modemInitializer;
    private final SmsIndexDetector indexDetector;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;
    private final TaskScheduler taskScheduler;
    private final AppConfig appConfig;

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-command-thread");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private Thread pollThread;
    private ScheduledFuture<?> unreadPollFuture;
    private final AtomicBoolean unreadPollInProgress = new AtomicBoolean(false);

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());

        running = true;
        readerService.start();

        commandExecutor.submit(modemInitializer::initialize).get(30, TimeUnit.SECONDS);

        pollThread = new Thread(this::pollLoop, "sms-poll-thread");
        pollThread.setDaemon(true);
        pollThread.start();

        unreadPollFuture = taskScheduler.scheduleAtFixedRate(
                this::scheduleUnreadPoll,
                Duration.ofMillis(appConfig.getUnreadPollIntervalMs()));

        log.info("SMS reader runtime started.");
    }

    private void pollLoop() {
        log.info("Entering main poll loop.");

        while (running) {
            List<Integer> newIndexes = indexDetector.detect();
            for (int index : newIndexes) {
                commandExecutor.submit(() -> processIncomingSms(index));
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.debug("Poll loop exiting.");
    }

    private void processIncomingSms(int index) {
        Optional<SmsMessage> msgOpt = smsService.readAndParse(index);
        msgOpt.ifPresent(msg -> {
            try {
                redisPublisher.publish(msg);
            } catch (Exception e) {
                log.error("Redis publish failed for SMS index={}: {}", index, e.getMessage(), e);
            }
        });
    }

    private void scheduleUnreadPoll() {
        if (!running) {
            return;
        }

        if (!unreadPollInProgress.compareAndSet(false, true)) {
            log.info("Skipping unread SMS schedule because the previous run is still queued/running.");
            return;
        }

        try {
            commandExecutor.submit(this::processScheduledUnreadSms);
        } catch (Exception e) {
            unreadPollInProgress.set(false);
            log.warn("Could not submit unread SMS schedule: {}", e.getMessage());
        }
    }

    private void processScheduledUnreadSms() {
        try {
            List<Integer> unreadIndexes = smsService.listUnreadIndexes();
            if (unreadIndexes.isEmpty()) {
                log.info("Scheduled unread SMS scan found no messages.");
                return;
            }

            log.info("Scheduled unread SMS scan found indexes: {}", unreadIndexes);
            for (int index : unreadIndexes) {
                Optional<SmsMessage> msgOpt = smsService.readAndParse(index);
                msgOpt.ifPresent(msg -> {
                    try {
                        redisPublisher.publishIfNewerThanCurrent(msg);
                    } catch (Exception e) {
                        log.error("Conditional Redis publish failed for scheduled SMS index={}: {}",
                                index, e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Scheduled unread SMS scan failed: {}", e.getMessage(), e);
        } finally {
            unreadPollInProgress.set(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!running && commandExecutor.isShutdown()) {
            return;
        }

        log.info("Graceful shutdown requested...");
        running = false;

        if (pollThread != null) {
            pollThread.interrupt();
        }

        if (unreadPollFuture != null) {
            unreadPollFuture.cancel(false);
        }

        commandExecutor.shutdown();
        try {
            if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                commandExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            commandExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        readerService.stop();
        portManager.close();

        log.info("Shutdown complete.");
    }
}
