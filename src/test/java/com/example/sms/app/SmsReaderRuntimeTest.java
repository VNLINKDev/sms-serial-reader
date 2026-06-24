package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;
import com.example.sms.telegram.TelegramNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsReaderRuntimeTest {

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

    private SmsReaderRuntime runtime;

    @BeforeEach
    void setUp() {
        lenient().when(appConfig.getUnreadPollIntervalMs()).thenReturn(100L); // fast polling for tests
        lenient().when(appConfig.isDeleteSmsAfterRead()).thenReturn(true);

        runtime = new SmsReaderRuntime(
                portManager,
                modemInitializer,
                smsService,
                redisPublisher,
                telegramNotifier,
                appConfig
        );
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
    }

    @Test
    @Timeout(value = 5, unit = SECONDS)
    void testRuntimeLifecycleAndScanFlow() throws Exception {
        SmsMessage msg = new SmsMessage(1, "TX_TEST_1", "123456", OffsetDateTime.now());
        
        // Setup smsService to return a message on the first call, then empty lists
        AtomicInteger callCount = new AtomicInteger(0);
        when(smsService.readAndParseAll()).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                return List.of(msg);
            }
            return Collections.emptyList();
        });

        // Run runtime in a separate thread so it doesn't block the test
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.run();
            } catch (Exception e) {
                fail("Runtime failed to run: " + e.getMessage());
            }
        });
        runtimeThread.start();

        // Verify that modem initializer is called
        verify(modemInitializer, timeout(1000).times(1)).initialize();

        // Verify message is published to Redis and Telegram
        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(redisPublisher, times(1)).publish(msg);
            verify(telegramNotifier, times(1)).sendSync(msg);
        });

        // Verify delete loop executed deleteSms and cleanupOldSms
        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(smsService, times(1)).deleteSms(1);
            verify(smsService, times(1)).cleanupOldSms(anyList());
        });

        // Shutdown and verify threads stop
        runtime.shutdown();
        runtimeThread.join(2000);
        assertFalse(runtimeThread.isAlive());
    }

    @Test
    @Timeout(value = 5, unit = SECONDS)
    void testTelegramPublishErrorDoesNotPreventRedisPublish() throws Exception {
        SmsMessage msg = new SmsMessage(1, "TX_TEST_ERR", "999999", OffsetDateTime.now());

        when(smsService.readAndParseAll()).thenReturn(List.of(msg));
        // Mock Telegram to throw exception
        doThrow(new RuntimeException("Telegram API down")).when(telegramNotifier).sendSync(msg);

        // Run runtime in a separate thread
        Thread runtimeThread = new Thread(() -> {
            try {
                runtime.run();
            } catch (Exception e) {
                fail("Runtime failed to run: " + e.getMessage());
            }
        });
        runtimeThread.start();

        // Verify Redis publisher still receives the message and executes successfully
        await().atMost(2, SECONDS).untilAsserted(() -> {
            verify(redisPublisher, times(1)).publish(msg);
            verify(telegramNotifier, atLeastOnce()).sendSync(msg);
        });

        runtime.shutdown();
        runtimeThread.join(1000);
    }
}
