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
        int keepAliveDays = config.getKeepAliveSmsIntervalDays();
        log.info("Khởi tạo lịch gửi SMS mỗi {} ngày (delay khởi động: 30s). phoneNumber={}", keepAliveDays, config.getPhoneNumber());

        scheduler.scheduleWithFixedDelay(this::execute, 30, keepAliveDays * 86400L, TimeUnit.SECONDS);
    }

    private void execute() {

        String phoneNumber = config.getPhoneNumber();
        String keepAliveMsg = config.getKeepAliveSmsContent();

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("PHONE_NUMBER chưa được cấu hình.");
            return;
        }

        try {
            synchronized (modemLock) {

                log.info("Bắt đầu gửi SMS định kỳ tới {}", phoneNumber);

                var sms = sendSms(phoneNumber, keepAliveMsg);

                log.info("Đã gửi SMS {} định kỳ tới {}", sms, phoneNumber);
            }

        } catch (Exception e) {
            log.error("Gửi SMS định kỳ thất bại. phoneNumber={}, error={}", phoneNumber, e.getMessage(), e);
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
