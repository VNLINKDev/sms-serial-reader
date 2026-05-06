package com.example.sms.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void bindsSpringProperties() {
        contextRunner
                .withPropertyValues(
                        "sms.serial.port=COM9",
                        "sms.serial.baud-rate=9600",
                        "sms.redis.host=redis.local",
                        "sms.redis.port=6380",
                        "sms.redis.database=2",
                        "sms.redis.queue-name=sms:test",
                        "sms.redis.mode=PUBSUB",
                        "sms.redis.publish-retries=5",
                        "sms.behavior.delete-sms-after-read=true"
                )
                .run(context -> {
                    AppConfig config = context.getBean(AppConfig.class);

                    assertThat(config.getSerialPort()).isEqualTo("COM9");
                    assertThat(config.getBaudRate()).isEqualTo(9600);
                    assertThat(config.getRedisHost()).isEqualTo("redis.local");
                    assertThat(config.getRedisPort()).isEqualTo(6380);
                    assertThat(config.getRedisDatabase()).isEqualTo(2);
                    assertThat(config.getRedisQueueName()).isEqualTo("sms:test");
                    assertThat(config.getRedisMode()).isEqualTo(AppConfig.RedisMode.PUBSUB);
                    assertThat(config.getRedisPublishRetries()).isEqualTo(5);
                    assertThat(config.isDeleteSmsAfterRead()).isTrue();
                });
    }

    @Test
    void requiresSerialPort() {
        contextRunner
                .withPropertyValues("sms.serial.port=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppConfig.class)
    static class Config {
    }
}
