package com.example.sms;

import com.example.sms.config.AppConfig;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.serial.AtCommandClient;
import com.example.sms.modem.ModemInitializer;
import com.example.sms.smsreader.SmsParser;
import com.example.sms.smsreader.SmsService;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.telegram.TelegramNotifier;
import com.example.sms.app.SmsReaderRuntime;

/**
 * Entry Point (Bootstrap Class) của ứng dụng Java SE thuần.
 *
 * Chịu trách nhiệm khởi tạo cấu hình, kết nối Serial Port, Redis,
 * và điều phối Core Runtime.
 */
public class SmsReaderApplication {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("Starting pure Java SMS Serial Reader (Vanilla)...");
        System.out.println("==================================================");

        // 1. Khởi tạo cấu hình từ biến môi trường
        AppConfig config = new AppConfig();

        // 2. Khởi tạo và mở cổng Serial Port
        SerialPortManager portManager = new SerialPortManager(config);
        try {
            portManager.open();
        } catch (Exception e) {
            System.err.println("CRITICAL: Failed to open serial port on startup: " + e.getMessage());
            System.exit(1);
        }

        // 3. Khởi tạo AT Command Client (đọc/ghi trực tiếp serial port)
        AtCommandClient atClient = new AtCommandClient(portManager);
        ModemInitializer modemInitializer = new ModemInitializer(atClient);

        // 4. Khởi tạo Service xử lý nghiệp vụ SMS
        SmsParser smsParser = new SmsParser(config.getSmsOtpPattern());
        SmsService smsService = new SmsService(atClient, smsParser, config);

        // 5. Khởi tạo Redis integration
        RedisPublisher redisPublisher = new RedisPublisher(config);
        try {
            redisPublisher.connect();
        } catch (Exception e) {
            System.err.println("CRITICAL: Failed to connect to Redis on startup: " + e.getMessage());
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
                smsService, redisPublisher, telegramNotifier, config);

        try {
            runtime.run();
        } catch (Exception e) {
            System.err.println("CRITICAL: SMS Reader Runtime run failed: " + e.getMessage());
            redisPublisher.close();
            portManager.close();
            System.exit(1);
        }

        // 8. Đăng ký JVM Shutdown Hook để tắt ứng dụng an toàn (Graceful Shutdown)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("==================================================");
            System.out.println("JVM Shutdown Hook triggered. Shutting down gracefully...");
            System.out.println("==================================================");

            try {
                runtime.shutdown();
            } catch (Exception e) {
                System.err.println("Error shutting down runtime: " + e.getMessage());
            }

            try {
                redisPublisher.close();
            } catch (Exception e) {
                System.err.println("Error closing Redis publisher: " + e.getMessage());
            }

            System.out.println("Shutdown complete. Bye!");
        }));
    }
}