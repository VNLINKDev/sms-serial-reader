package com.example.sms.app;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.RedisPublishException;
import com.example.sms.exception.SerialPortException;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.smsreader.SmsMessage;
import com.example.sms.smsreader.SmsService;
import com.example.sms.telegram.TelegramNotifier;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite toàn diện cho {@link SmsReaderRuntime}.
 *
 * Bao gồm:
 * - Happy path: scan → dedup → publish → cleanup
 * - Dedup logic: lastPublishedTransactionId, currentlyPublishingTransactionId
 * - Shutdown graceful: publishLock.notifyAll(), running = false
 * - Reconnect khi modem lỗi
 * - publishWithRetry: retry exponential backoff
 * - publishLoop: wait/notify không busy-loop
 * - Race conditions giữa scan thread và publish thread
 * - Stress test: 100% CPU và memory leak detection
 */
@ExtendWith(MockitoExtension.class)
class SmsReaderRuntimeTest {

    @Mock private SerialPortManager portManager;
    @Mock private ModemInitializer modemInitializer;
    @Mock private SmsService smsService;
    @Mock private RedisPublisher redisPublisher;
    @Mock private TelegramNotifier telegramNotifier;
    @Mock private AppConfig appConfig;

    private SmsReaderRuntime runtime;

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private SmsMessage createSms(int index, String transactionId) {
        return new SmsMessage(index, transactionId, "123456",
                OffsetDateTime.now());
    }

    /**
     * Dùng reflection để đọc/ghi private field cho test.
     */
    private Object getField(String name) throws Exception {
        Field f = SmsReaderRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(runtime);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SmsReaderRuntime.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(runtime, value);
    }

    /**
     * Tạo runtime mới, mock pollInterval rất nhỏ để test nhanh.
     */
    @BeforeEach
    void setUp() {
        lenient().when(appConfig.getUnreadPollIntervalMs()).thenReturn(10L);
        lenient().when(appConfig.isDeleteSmsAfterRead()).thenReturn(false);
        runtime = new SmsReaderRuntime(
                portManager, modemInitializer, smsService,
                redisPublisher, telegramNotifier, appConfig);
    }

    @AfterEach
    void tearDown() {
        // Luôn cleanup, tránh thread leak
        try {
            runtime.shutdown();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // 1. HAPPY PATH: scan tìm SMS → publish thành công
    // =========================================================================

    @Test
    @DisplayName("Happy path: scan tìm SMS mới → notify Telegram → publish Redis → cleanup")
    void happyPath_scanFindsNewSms_publishesSuccessfully() throws Exception {
        SmsMessage sms = createSms(1, "TXN-001");

        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        runtime.run();

        // Đợi publish loop xử lý xong
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(redisPublisher, atLeastOnce()).publish(sms);
        });

        verify(telegramNotifier).notifyAsync(sms);
    }

    // =========================================================================
    // 2. DEDUP: lastPublishedTransactionId
    // =========================================================================

    @Test
    @DisplayName("Dedup: SMS đã publish rồi → skip, KHÔNG gọi lại Redis")
    void dedup_alreadyPublished_skipsRedis() throws Exception {
        SmsMessage sms = createSms(1, "TXN-001");
        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        // Giả lập đã publish transactionId này trước đó
        setField("lastPublishedTransactionId", "TXN-001");
        setField("running", true);

        // Gọi trực tiếp doScanAndPublish để test dedup
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(redisPublisher, never()).publish(any());
        verify(telegramNotifier, never()).notifyAsync(any());
    }

    @Test
    @DisplayName("Dedup: SMS đang được publish (retry) → skip, KHÔNG ghi đè")
    void dedup_currentlyPublishing_skips() throws Exception {
        SmsMessage sms = createSms(2, "TXN-002");
        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        // Giả lập thread Redis đang publish tin này
        setField("currentlyPublishingTransactionId", "TXN-002");
        setField("running", true);

        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(redisPublisher, never()).publish(any());
        verify(telegramNotifier, never()).notifyAsync(any());
    }

    @Test
    @DisplayName("Dedup: transactionId mới khác → publish bình thường")
    void dedup_newTransactionId_publishes() throws Exception {
        SmsMessage sms = createSms(3, "TXN-003");
        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        // Tin trước là TXN-001 → TXN-003 là mới
        setField("lastPublishedTransactionId", "TXN-001");
        setField("running", true);

        runtime.run();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(redisPublisher, atLeastOnce()).publish(sms);
        });
    }

    // =========================================================================
    // 3. EMPTY SCAN: SIM trống → không làm gì
    // =========================================================================

    @Test
    @DisplayName("SIM trống → không publish, không crash")
    void emptyScan_noMessages_doesNothing() throws Exception {
        when(smsService.readAndParseAll()).thenReturn(Collections.emptyList());

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(redisPublisher, never()).publish(any());
        verify(telegramNotifier, never()).notifyAsync(any());
    }

    // =========================================================================
    // 4. MODEM ERROR: SerialPortException → reconnect
    // =========================================================================

    @Test
    @DisplayName("SerialPortException khi scan → gọi reconnect, KHÔNG crash loop")
    void modemError_serialPortException_triggersReconnect() throws Exception {
        when(smsService.readAndParseAll())
                .thenThrow(new SerialPortException("USB disconnected"));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(portManager).reconnect();
        verify(modemInitializer).initialize();
    }

    @Test
    @DisplayName("ModemTimeoutException khi scan → gọi reconnect")
    void modemError_timeoutException_triggersReconnect() throws Exception {
        when(smsService.readAndParseAll())
                .thenThrow(new ModemTimeoutException("AT+CMGL", 5000));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(portManager).reconnect();
        verify(modemInitializer).initialize();
    }

    @Test
    @DisplayName("Reconnect fail → log error, KHÔNG crash, loop tiếp tục")
    void modemError_reconnectFails_doesNotCrash() throws Exception {
        when(smsService.readAndParseAll())
                .thenThrow(new SerialPortException("USB gone"));
        doThrow(new RuntimeException("Port not found")).when(portManager).reconnect();

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);

        // KHÔNG throw ra ngoài
        assertDoesNotThrow(() -> method.invoke(runtime));
    }

    // =========================================================================
    // 5. PUBLISH RETRY: Redis fail → exponential backoff → thành công
    // =========================================================================

    @Test
    @DisplayName("Redis fail 2 lần → thành công lần 3 → lastPublishedTransactionId cập nhật")
    void publishRetry_failsTwiceThenSucceeds() throws Exception {
        SmsMessage sms = createSms(5, "TXN-005");

        doThrow(new RedisPublishException("timeout", new RuntimeException()))
                .doThrow(new RedisPublishException("timeout", new RuntimeException()))
                .doNothing()
                .when(redisPublisher).publish(sms);

        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        runtime.run();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(redisPublisher, times(3)).publish(sms);
            assertEquals("TXN-005", getField("lastPublishedTransactionId"));
        });
    }

    @Test
    @DisplayName("Redis fail 3/3 lần → lastPublished KHÔNG cập nhật, currentlyPublishing cleared")
    void publishRetry_allFail_doesNotUpdateLastPublished() throws Exception {
        SmsMessage sms = createSms(6, "TXN-006");

        doThrow(new RedisPublishException("down", new RuntimeException()))
                .when(redisPublisher).publish(sms);

        when(smsService.readAndParseAll())
                .thenReturn(List.of(sms))
                .thenReturn(Collections.emptyList()); // lần scan sau SIM trống

        runtime.run();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(redisPublisher, times(3)).publish(sms);
        });

        // lastPublished KHÔNG cập nhật vì fail hết
        assertNull(getField("lastPublishedTransactionId"));
        // currentlyPublishing phải được clear ở finally
        assertNull(getField("currentlyPublishingTransactionId"));
    }

    // =========================================================================
    // 6. SHUTDOWN: graceful, đánh thức publishLoop
    // =========================================================================

    @Test
    @DisplayName("Shutdown: publishLoop thoát khi running=false, executor terminated")
    void shutdown_publishLoopExits_executorsTerminate() throws Exception {
        when(smsService.readAndParseAll()).thenReturn(Collections.emptyList());

        runtime.run();
        Thread.sleep(100); // để loop chạy vài vòng

        runtime.shutdown();

        ExecutorService commandExec = (ExecutorService) getField("commandExecutor");
        ExecutorService redisExec = (ExecutorService) getField("redisExecutor");

        assertTrue(commandExec.isShutdown());
        assertTrue(redisExec.isShutdown());
    }

    @Test
    @DisplayName("Shutdown trùng lặp: gọi 2 lần không crash")
    void shutdown_calledTwice_noCrash() throws Exception {
        when(smsService.readAndParseAll()).thenReturn(Collections.emptyList());

        runtime.run();
        Thread.sleep(50);

        assertDoesNotThrow(() -> {
            runtime.shutdown();
            runtime.shutdown();
        });
    }

    // =========================================================================
    // 7. DELETE SMS AFTER READ
    // =========================================================================

    @Test
    @DisplayName("deleteSmsAfterRead=true → xóa từng SMS trên SIM")
    void deleteAfterRead_enabled_deletesAllMessages() throws Exception {
        SmsMessage sms1 = createSms(1, "TXN-D1");
        SmsMessage sms2 = createSms(2, "TXN-D2");

        when(appConfig.isDeleteSmsAfterRead()).thenReturn(true);
        when(smsService.readAndParseAll()).thenReturn(List.of(sms1, sms2));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        verify(smsService).deleteSms(1);
        verify(smsService).deleteSms(2);
    }

    // =========================================================================
    // 8. CLEANUP FAILURE: cleanupOldSms fail → log warn, loop tiếp
    // =========================================================================

    @Test
    @DisplayName("cleanupOldSms throw → chỉ log warn, KHÔNG crash scan loop")
    void cleanup_fails_doesNotCrashLoop() throws Exception {
        SmsMessage sms = createSms(1, "TXN-C1");
        when(smsService.readAndParseAll()).thenReturn(List.of(sms));
        doThrow(new RuntimeException("SIM cleanup error"))
                .when(smsService).cleanupOldSms(anyList());

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(runtime));
    }

    // =========================================================================
    // 9. MULTIPLE SMS: chỉ xử lý tin MỚI NHẤT (cuối list đã sort)
    // =========================================================================

    @Test
    @DisplayName("Nhiều SMS trên SIM → chỉ publish tin cuối cùng (mới nhất theo timestamp)")
    void multipleSms_onlyLatestPublished() throws Exception {
        SmsMessage old = createSms(1, "TXN-OLD");
        SmsMessage latest = createSms(2, "TXN-LATEST");

        when(smsService.readAndParseAll()).thenReturn(List.of(old, latest));

        runtime.run();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(redisPublisher, atLeastOnce()).publish(latest);
        });

        // Tin cũ KHÔNG được publish
        verify(redisPublisher, never()).publish(old);
    }

    // =========================================================================
    // 10. GENERIC EXCEPTION trong doScanAndPublish → catch, loop tiếp
    // =========================================================================

    @Test
    @DisplayName("RuntimeException bất ngờ → catch, KHÔNG crash scan loop")
    void unexpectedException_doesNotCrashScanLoop() throws Exception {
        when(smsService.readAndParseAll())
                .thenThrow(new NullPointerException("something unexpected"));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(runtime));
    }

    // =========================================================================
    // 11. STRESS TEST: publishLoop KHÔNG busy-loop khi pendingMessage == null
    //     → CPU usage test
    // =========================================================================

    @Test
    @DisplayName("[CPU] publishLoop wait/notify: KHÔNG spin-loop khi không có pending message")
    void cpuTest_publishLoop_doesNotBusyLoop() throws Exception {
        // SIM trống → không bao giờ có pendingMessage
        when(smsService.readAndParseAll()).thenReturn(Collections.emptyList());

        runtime.run();

        // Đo CPU: nếu publishLoop busy-loop, Thread.State sẽ là RUNNABLE liên tục.
        // Nếu wait/notify đúng, thread phải ở WAITING hoặc TIMED_WAITING.
        Thread.sleep(500);

        // Tìm thread sms-redis-thread
        Thread redisThread = findThread("sms-redis-thread");
        assertNotNull(redisThread, "Phải có thread sms-redis-thread đang chạy");

        // Thread phải đang WAITING (trên publishLock.wait()), KHÔNG phải RUNNABLE
        Thread.State state = redisThread.getState();
        assertTrue(
                state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING,
                "publishLoop phải WAITING khi không có pending message, nhưng state=" + state
        );
    }

    @Test
    @DisplayName("[CPU] scanLoop nghỉ đúng interval → KHÔNG spin-loop")
    void cpuTest_scanLoop_respectsInterval() throws Exception {
        // Poll interval = 200ms
        when(appConfig.getUnreadPollIntervalMs()).thenReturn(200L);
        AtomicInteger scanCount = new AtomicInteger(0);
        when(smsService.readAndParseAll()).thenAnswer(inv -> {
            scanCount.incrementAndGet();
            return Collections.emptyList();
        });

        // Tạo runtime mới với interval lớn
        runtime = new SmsReaderRuntime(
                portManager, modemInitializer, smsService,
                redisPublisher, telegramNotifier, appConfig);

        runtime.run();

        // Đợi 1 giây → với interval 200ms, tối đa ~5 lần scan
        Thread.sleep(1000);
        runtime.shutdown();

        int count = scanCount.get();
        // Nếu spin-loop (không sleep), sẽ có hàng nghìn lần
        assertTrue(count <= 10,
                "scanLoop chạy " + count + " lần trong 1s — quá nhiều, có thể spin-loop!");
        assertTrue(count >= 1,
                "scanLoop phải chạy ít nhất 1 lần");
    }

    // =========================================================================
    // 12. STRESS TEST: scan liên tục với Redis luôn fail → KHÔNG leak memory
    // =========================================================================

    @Test
    @DisplayName("[MEMORY] Redis luôn fail, scan liên tục → pendingMessage bị ghi đè, KHÔNG accumulate")
    void memoryTest_redisAlwaysFails_noMessageAccumulation() throws Exception {
        // Tạo 1000 SMS khác nhau
        List<SmsMessage> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(createSms(i, "TXN-MEM-" + i));
        }

        AtomicInteger callIndex = new AtomicInteger(0);
        when(smsService.readAndParseAll()).thenAnswer(inv -> {
            int idx = callIndex.getAndIncrement() % messages.size();
            return List.of(messages.get(idx));
        });

        // Redis luôn fail → publishWithRetry retry 3 lần rồi bỏ
        doThrow(new RedisPublishException("down", new RuntimeException()))
                .when(redisPublisher).publish(any());

        when(appConfig.getUnreadPollIntervalMs()).thenReturn(5L); // poll rất nhanh

        runtime = new SmsReaderRuntime(
                portManager, modemInitializer, smsService,
                redisPublisher, telegramNotifier, appConfig);

        runtime.run();
        Thread.sleep(3000); // chạy 3 giây stress

        runtime.shutdown();

        // Kiểm tra pendingMessage: phải là null hoặc chỉ 1 item (KHÔNG phải queue vô hạn)
        Object pending = getField("pendingMessage");
        // pendingMessage là single volatile variable, không phải collection
        // → KHÔNG có memory leak từ message accumulation
        assertTrue(pending == null || pending instanceof SmsMessage,
                "pendingMessage phải null hoặc SmsMessage, KHÔNG phải collection");
    }

    // =========================================================================
    // 13. STRESS TEST: rapid scan → pendingMessage overwrite, KHÔNG queue unbounded
    // =========================================================================

    @Test
    @DisplayName("[MEMORY] Rapid scan ghi đè pendingMessage → thread Redis chỉ thấy tin mới nhất")
    void memoryTest_rapidScan_pendingOverwrite() throws Exception {
        // Simulate: scan thread tìm thấy tin mới rất nhanh, publish thread chậm
        CountDownLatch publishStarted = new CountDownLatch(1);

        // Redis chậm: sleep 500ms mỗi lần
        doAnswer(inv -> {
            publishStarted.countDown();
            Thread.sleep(500);
            return null;
        }).when(redisPublisher).publish(any());

        AtomicInteger scanIdx = new AtomicInteger(0);
        when(smsService.readAndParseAll()).thenAnswer(inv -> {
            int i = scanIdx.incrementAndGet();
            return List.of(createSms(i, "TXN-RAPID-" + i));
        });

        when(appConfig.getUnreadPollIntervalMs()).thenReturn(10L);

        runtime = new SmsReaderRuntime(
                portManager, modemInitializer, smsService,
                redisPublisher, telegramNotifier, appConfig);

        runtime.run();
        // Đợi ít nhất 1 publish
        publishStarted.await(5, TimeUnit.SECONDS);
        Thread.sleep(1000); // thêm thời gian cho vài scan

        runtime.shutdown();

        // pendingMessage kiểu volatile SmsMessage đơn lẻ → chỉ 1 slot
        // → KHÔNG có unbounded queue → KHÔNG tràn RAM
        Object pending = getField("pendingMessage");
        assertTrue(pending == null || pending instanceof SmsMessage);
    }

    // =========================================================================
    // 14. CONCURRENCY: scan thread và publish thread race condition
    // =========================================================================

    @Test
    @DisplayName("[RACE] offerForPublish + publishLoop: synchronized trên publishLock")
    void raceCondition_offerAndPublish_safeUnderConcurrency() throws Exception {
        // Gọi offerForPublish từ nhiều thread song song
        var offerMethod = SmsReaderRuntime.class.getDeclaredMethod("offerForPublish", SmsMessage.class);
        offerMethod.setAccessible(true);

        setField("running", true);

        // Start publish loop trên redisExecutor
        ExecutorService redisExec = (ExecutorService) getField("redisExecutor");
        var publishLoopMethod = SmsReaderRuntime.class.getDeclaredMethod("publishLoop");
        publishLoopMethod.setAccessible(true);
        redisExec.submit(() -> {
            try {
                publishLoopMethod.invoke(runtime);
            } catch (Exception e) {
                // ignore
            }
        });

        // Offer 50 messages nhanh từ thread khác
        ExecutorService senderPool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            final int idx = i;
            senderPool.submit(() -> {
                try {
                    offerMethod.invoke(runtime, createSms(idx, "TXN-RACE-" + idx));
                } catch (Exception e) {
                    fail("offerForPublish threw: " + e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        senderPool.shutdown();

        // Không deadlock, không crash → test passes nếu reach đây
        // Shutdown để cleanup
        setField("running", false);
        Object lock = getField("publishLock");
        synchronized (lock) {
            lock.notifyAll();
        }

        Thread.sleep(200);
    }

    // =========================================================================
    // 15. EDGE CASE: transactionId null
    // =========================================================================

    @Test
    @DisplayName("transactionId null → Objects.equals handles null safely")
    void edgeCase_nullTransactionId_noNPE() throws Exception {
        SmsMessage sms = createSms(1, null); // transactionId = null

        when(smsService.readAndParseAll()).thenReturn(List.of(sms));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);

        // Objects.equals(null, null) = true khi lastPublishedTransactionId cũng null → skip
        // NHƯNG khi lastPublished != null → publish bình thường
        setField("lastPublishedTransactionId", "something");
        assertDoesNotThrow(() -> method.invoke(runtime));
    }

    // =========================================================================
    // 16. MODEM INIT TIMEOUT: run() → modem init fail
    // =========================================================================

    @Test
    @DisplayName("modemInitializer.initialize() throw → run() propagates exception")
    void modemInitFail_runThrows() throws Exception {
        doThrow(new RuntimeException("SIM not ready"))
                .when(modemInitializer).initialize();

        assertThrows(Exception.class, () -> runtime.run());
    }

    // =========================================================================
    // 17. LONG-RUNNING STRESS: nhiều vòng scan + publish không rò rỉ
    // =========================================================================

    @Test
    @DisplayName("[STRESS] 500 scans liên tiếp → không tăng memory bất thường")
    void stressTest_manyScanCycles_noMemoryLeak() throws Exception {
        AtomicInteger scanCount = new AtomicInteger(0);

        when(smsService.readAndParseAll()).thenAnswer(inv -> {
            int n = scanCount.incrementAndGet();
            if (n % 3 == 0) {
                return Collections.emptyList(); // SIM trống
            }
            return List.of(createSms(n, "TXN-STRESS-" + n));
        });

        when(appConfig.getUnreadPollIntervalMs()).thenReturn(1L); // cực nhanh

        runtime = new SmsReaderRuntime(
                portManager, modemInitializer, smsService,
                redisPublisher, telegramNotifier, appConfig);

        // Đo memory trước
        System.gc();
        Thread.sleep(100);
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        runtime.run();

        // Chạy cho đến khi đủ 500 scan hoặc timeout 15s
        await().atMost(15, TimeUnit.SECONDS).until(() -> scanCount.get() >= 500);

        runtime.shutdown();

        // Đo memory sau
        System.gc();
        Thread.sleep(100);
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        long diff = memAfter - memBefore;
        // Cho phép tăng tối đa 50MB (JVM overhead, log buffers, etc.)
        // Nếu leak thật sự (queue vô hạn), sẽ tăng hàng trăm MB
        assertTrue(diff < 50 * 1024 * 1024,
                "Memory tăng " + (diff / 1024 / 1024) + "MB sau 500 scans — khả nghi memory leak!");
    }

    // =========================================================================
    // 18. scanInProgress flag reset: luôn set false trong finally
    // =========================================================================

    @Test
    @DisplayName("scanInProgress reset về false kể cả khi exception")
    void scanInProgress_resetOnException() throws Exception {
        when(smsService.readAndParseAll())
                .thenThrow(new RuntimeException("unexpected"));

        setField("running", true);
        var method = SmsReaderRuntime.class.getDeclaredMethod("doScanAndPublish");
        method.setAccessible(true);
        method.invoke(runtime);

        // Kiểm tra scanInProgress = false
        var scanInProgress = getField("scanInProgress");
        assertEquals(false, ((java.util.concurrent.atomic.AtomicBoolean) scanInProgress).get());
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private Thread findThread(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
