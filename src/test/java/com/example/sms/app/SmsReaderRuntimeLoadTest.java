package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;
import com.example.sms.telegram.TelegramNotifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsReaderRuntimeLoadTest {

    @Mock
    private SerialPortManager portManager;

    @Mock
    private ModemInitializer modemInitializer;

    @Mock
    private SmsService smsService;

    @Mock
    private RedisPublisher redisPublisher;

    @Mock
    private TelegramNotifier telegramNotifier;

    @Mock
    private AppConfig appConfig;

    @Test
    void runFiveMinuteLoadTest() throws Exception {
        // Only run when explicitly requested via -DrunLoadTest=true
        Assumptions.assumeTrue("true".equals(System.getProperty("runLoadTest")),
                "Skipping load test. Enable by running with -DrunLoadTest=true");

        System.out.println("==================================================");
        System.out.println("Starting 5-minute mock Load & Leak Test...");
        System.out.println("==================================================");

        // Configuration setup
        when(appConfig.getUnreadPollIntervalMs()).thenReturn(500L); // scan twice per second
        when(appConfig.isDeleteSmsAfterRead()).thenReturn(true);

        SmsReaderRuntime runtime = new SmsReaderRuntime(
                portManager,
                modemInitializer,
                smsService,
                redisPublisher,
                telegramNotifier,
                appConfig
        );

        // Simulation setup: generate a new message on every scan (every 500ms)
        AtomicInteger msgIndex = new AtomicInteger(1);
        when(smsService.readAndParseAll()).thenAnswer(invocation -> {
            int idx = msgIndex.getAndIncrement();
            SmsMessage msg = new SmsMessage(idx, "TX_LOAD_" + idx, "OTP_" + idx, OffsetDateTime.now());
            return List.of(msg);
        });

        // Run the orchestrator in a separate thread
        Thread runnerThread = new Thread(() -> {
            try {
                runtime.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        runnerThread.start();

        // Monitoring variables
        long startTime = System.currentTimeMillis();
        long durationMs = TimeUnit.MINUTES.toMillis(5); // 5 minutes
        long endTime = startTime + durationMs;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        com.sun.management.OperatingSystemMXBean osMXBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        
        // Log resource usage statistics every 10 seconds
        monitorExecutor.scheduleAtFixedRate(() -> {
            Runtime rt = Runtime.getRuntime();
            long heapUsedBytes = rt.totalMemory() - rt.freeMemory();
            long heapMaxBytes = rt.maxMemory();
            int threadCount = threadMXBean.getThreadCount();
            double processCpu = osMXBean.getProcessCpuLoad() * 100.0;
            long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;

            System.out.printf("[MONITOR] Elapsed: %3ds | CPU: %5.2f%% | Heap: %4d MB / %4d MB | Threads: %3d | Processed Messages: %d%n",
                    elapsedSec,
                    processCpu < 0 ? 0.0 : processCpu,
                    heapUsedBytes / (1024 * 1024),
                    heapMaxBytes / (1024 * 1024),
                    threadCount,
                    msgIndex.get() - 1
            );
        }, 5, 10, TimeUnit.SECONDS);

        // Wait for 5 minutes
        while (System.currentTimeMillis() < endTime) {
            Thread.sleep(1000);
        }

        System.out.println("==================================================");
        System.out.println("Load test completed. Shutting down...");
        System.out.println("==================================================");

        // Cleanup
        monitorExecutor.shutdown();
        runtime.shutdown();
        runnerThread.join(5000);
        
        assertFalse(runnerThread.isAlive(), "Orchestrator thread did not shut down cleanly.");

        // Print final reports
        Runtime rt = Runtime.getRuntime();
        long heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        System.out.printf("Final Report:%n");
        System.out.printf("- Total Processed Messages: %d%n", msgIndex.get() - 1);
        System.out.printf("- Final JVM Heap Used: %d MB%n", heapUsedBytes / (1024 * 1024));
        System.out.printf("- Final Thread Count: %d%n", threadMXBean.getThreadCount());
        System.out.println("==================================================");
    }
}
