package com.example.sms;

import com.example.sms.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Bootstrap class của Spring Boot application.
 *
 * {@link EnableConfigurationProperties} đăng ký {@link AppConfig} để toàn bộ
 * cấu hình {@code sms.*} được bind và validate khi application context khởi tạo.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class SmsReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsReaderApplication.class, args);
    }
}
