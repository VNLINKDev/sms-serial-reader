package com.example.sms.schedule;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PublishSmsSchedule implements AutoCloseable {
    private final AppConfig config;
    private final SmsService smsService;   // đổi từ AtCommandClient -> SmsService
    private final Object modemLock;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sms-send-scheduler");
        thread.setDaemon(false);
        return thread;
    });

    public PublishSmsSchedule(AppConfig config, SmsService smsService, Object modemLock) {
        this.config = config;
        this.smsService = smsService;
        this.modemLock = modemLock;
    }

    public void start() {
        int keepAliveDays = Math.max(1, config.getKeepAliveSmsIntervalDays());
        long intervalSeconds = TimeUnit.DAYS.toSeconds(keepAliveDays);
        long configuredInitialDelay = config.getKeepAliveSmsInitialDelaySeconds();
        long initialDelaySeconds = configuredInitialDelay >= 0
                ? configuredInitialDelay
                : intervalSeconds;
        log.info("Khởi tạo lịch gửi SMS mỗi {} ngày; lần gửi đầu tiên sau {} giây. phoneNumber={}",
                keepAliveDays, initialDelaySeconds, config.getPhoneNumber());

        // Production có thể để initial delay âm (mặc định) để chờ đủ chu kỳ.
        // Môi trường test có thể cấu hình delay ngắn mà không sửa lại source code.
        scheduler.scheduleWithFixedDelay(
                this::execute,
                initialDelaySeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    private void execute() {

        String keepAlivePhoneNumber = config.getKeepAlivePhoneNumber();
        String keepAliveMsg = config.getKeepAliveSmsContent();

        if (keepAlivePhoneNumber == null || keepAlivePhoneNumber.isBlank()) {
            log.warn("KEEP_ALIVE_PHONE_NUMBER chưa được cấu hình.");
            return;
        }

        try {
            synchronized (modemLock) {

                log.info("Bắt đầu gửi SMS định kỳ tới {}", keepAlivePhoneNumber);

                var sms = sendSms(keepAlivePhoneNumber, keepAliveMsg);

                log.info("Đã gửi SMS {} định kỳ tới {}", sms, keepAlivePhoneNumber);
            }

        } catch (Exception e) {
            log.error("Gửi SMS định kỳ thất bại. phoneNumber={}, error={}", keepAlivePhoneNumber, e.getMessage(), e);
        }
    }

    private String sendSms(String phoneNumber, String content) {
        return smsService.sendSms(phoneNumber, content);
    }

    @Override
    public void close() {

        log.info("Đang dừng PublishSmsSchedule...");

        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
