package com.example.sms.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

@Configuration
public class SchedulerConfig {

    /**
     * Scheduler dành cho các tác vụ nền không cần transaction/context web,
     * hiện dùng để trigger quét SMS unread theo chu kỳ.
     *
     * Pool size 2 đủ để scheduler không bị block bởi một trigger chậm, nhưng
     * logic truy cập modem vẫn được serialize ở {@code SmsReaderRuntime}. Vì vậy
     * bean này chỉ chịu trách nhiệm phát tín hiệu lịch, không quyết định
     * concurrency với serial port.
     *
     * Virtual thread giúp giảm chi phí thread cho workload I/O nhẹ. NOTE:
     * nếu sau này thêm tác vụ CPU-bound vào scheduler này, cần đánh giá lại pool
     * riêng để tránh ảnh hưởng lịch quét SMS.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(
                2,
                Thread.ofVirtual().name("sms-unread-scheduler-", 0).factory());

        return new ConcurrentTaskScheduler(schedulerExecutor);
    }
}
