package com.example.sms.app;

import com.example.sms.modem.ModemInitializer;
import com.example.sms.modem.SmsIndexDetector;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.serial.SerialReaderService;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SmsReaderRuntime implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmsReaderRuntime.class);
    private static final int POLL_INTERVAL_MS = 100;

    private final SerialPortManager portManager;
    private final SerialReaderService readerService;
    private final ModemInitializer modemInitializer;
    private final SmsIndexDetector indexDetector;
    private final SmsService smsService;
    private final RedisPublisher redisPublisher;

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sms-command-thread");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private Thread pollThread;

    public SmsReaderRuntime(SerialPortManager portManager,
                            SerialReaderService readerService,
                            ModemInitializer modemInitializer,
                            SmsIndexDetector indexDetector,
                            SmsService smsService,
                            RedisPublisher redisPublisher) {
        this.portManager = portManager;
        this.readerService = readerService;
        this.modemInitializer = modemInitializer;
        this.indexDetector = indexDetector;
        this.smsService = smsService;
        this.redisPublisher = redisPublisher;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Available serial ports: {}", SerialPortManager.listAvailablePorts());

        running = true;
        readerService.start();

        commandExecutor.submit(modemInitializer::initialize).get(30, TimeUnit.SECONDS);

        pollThread = new Thread(this::pollLoop, "sms-poll-thread");
        pollThread.setDaemon(true);
        pollThread.start();

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
