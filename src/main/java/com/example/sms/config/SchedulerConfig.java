package com.example.sms.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

@Configuration
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(
                2,
                Thread.ofVirtual().name("sms-unread-scheduler-", 0).factory());

        return new ConcurrentTaskScheduler(schedulerExecutor);
    }
}
