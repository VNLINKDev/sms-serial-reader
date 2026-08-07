package com.example.sms.schedule;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PublishSmsSchedule implements AutoCloseable {

    private static final long PERIOD_DAYS = 5;

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
        log.info("Khởi tạo lịch gửi SMS mỗi {} ngày. phoneNumber={}", PERIOD_DAYS, config.getPhoneNumber());

        scheduler.scheduleWithFixedDelay(this::execute, 0, PERIOD_DAYS, TimeUnit.DAYS);
    }

    private void execute() {

        String phoneNumber = config.getPhoneNumber();

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("PHONE_NUMBER chưa được cấu hình.");
            return;
        }

        try {
            synchronized (modemLock) {

                log.info("Bắt đầu gửi SMS định kỳ tới {}", phoneNumber);

                sendSms(phoneNumber, "OTP");

                log.info("Đã gửi SMS định kỳ tới {}", phoneNumber);
            }

        } catch (Exception e) {
            log.error("Gửi SMS định kỳ thất bại. phoneNumber={}, error={}", phoneNumber, e.getMessage(), e);
        }
    }

    private void sendSms(String phoneNumber, String content) {
        smsService.sendSms(phoneNumber, content);
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
