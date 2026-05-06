package com.example.sms;

import com.example.sms.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class SmsReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmsReaderApplication.class, args);
    }
}
