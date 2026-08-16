package com.example.sms;

import com.example.sms.config.AppConfig;
import com.example.sms.schedule.PublishSmsSchedule;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.serial.AtCommandClient;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.smsreader.SmsParser;
import com.example.sms.smsreader.SmsService;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.telegram.TelegramNotifier;
import com.example.sms.app.SmsReaderRuntime;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry Point (Bootstrap Class) của ứng dụng Java SE thuần.
 *
 * Chịu trách nhiệm khởi tạo cấu hình, kết nối Serial Port, Redis,
 * và điều phối Core Runtime.
 */
@Slf4j
public class SmsReaderApplication {

    public static void main(String[] args) {
        log.info("SMS Reader đang khởi động");

        // 1. Khởi tạo cấu hình từ biến môi trường
        AppConfig config = new AppConfig();
        if (config.getPhoneNumber() == null) {
            log.error("Thiếu PHONE_NUMBER");
            System.exit(1);
        }

        // 2. Khởi tạo và mở cổng Serial Port
        SerialPortManager portManager = new SerialPortManager(config);
        try {
            portManager.open();
        } catch (Exception e) {
            log.error("Mở serial thất bại | error={}", e.getMessage());
            System.exit(1);
        }

        // 3. Khởi tạo AT Command Client (đọc/ghi trực tiếp serial port)
        AtCommandClient atClient = new AtCommandClient(portManager);
        Object modemLock = new Object(); // dùng chung để đồng bộ truy cập atClient giữa các thread
        ModemInitializer modemInitializer = new ModemInitializer(atClient);

        // 4. Khởi tạo Service xử lý nghiệp vụ SMS
        SmsParser smsParser = new SmsParser(config.getSmsOtpPattern());
        SmsService smsService = new SmsService(atClient, smsParser, config);

        PublishSmsSchedule smsSchedule = new PublishSmsSchedule(config, smsService, modemLock);

        // 5. Khởi tạo Redis integration
        RedisPublisher redisPublisher = new RedisPublisher(config);
        try {
            redisPublisher.connect();
        } catch (Exception e) {
            log.error("Kết nối Redis thất bại | error={}", e.getMessage());
            closeQuietly(redisPublisher, "Redis publisher");
            portManager.close();
            System.exit(1);
        }

        // 6. Khởi tạo Telegram Notifier
        // TelegramNotifier không còn quản lý executor nội bộ —
        // thread được điều phối hoàn toàn bởi SmsReaderRuntime.
        TelegramNotifier telegramNotifier = new TelegramNotifier(config);

        // 7. Khởi chạy Core Runtime
        SmsReaderRuntime runtime = new SmsReaderRuntime(
                portManager, modemInitializer,
                smsService, redisPublisher, telegramNotifier, config, modemLock);

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        AtomicBoolean shutdownStarted = new AtomicBoolean(false);

        // 8. Đăng ký JVM Shutdown Hook trước khi runtime start để mọi failure sau
        // điểm này đều đi qua cùng một đường cleanup.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Đang tắt ứng dụng");

            shutdownGracefully(runtime, smsSchedule, redisPublisher, shutdownStarted);
            shutdownLatch.countDown();
        }, "sms-reader-shutdown-hook"));

        try {
            runtime.run();
        } catch (Exception e) {
            log.error("Khởi động runtime thất bại | error={}", e.getMessage());
            shutdownGracefully(runtime, smsSchedule, redisPublisher, shutdownStarted);
            System.exit(1);
        }
        smsSchedule.start();

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGracefully(runtime, smsSchedule, redisPublisher, shutdownStarted);
            System.exit(1);
        }
    }

    private static void shutdownGracefully(
            SmsReaderRuntime runtime,
            PublishSmsSchedule smsSchedule,
            RedisPublisher redisPublisher,
            AtomicBoolean shutdownStarted) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            runtime.shutdown();
        } catch (Exception e) {
            log.error("Tắt runtime thất bại | error={}", e.getMessage());
        }

        closeQuietly(smsSchedule, "lịch gửi SMS định kỳ");
        closeQuietly(redisPublisher, "bộ phát Redis");
        log.info("Ứng dụng đã dừng");
    }

    private static void closeQuietly(AutoCloseable resource, String name) {
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("Đóng {} thất bại | error={}", name, e.getMessage());
        }
    }
}
