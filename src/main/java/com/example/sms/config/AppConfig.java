package com.example.sms.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sms")
@Data
public class AppConfig {

    @Valid
    private final Serial serial = new Serial();

    @Valid
    private final Redis redis = new Redis();

    @Valid
    private final Behavior behavior = new Behavior();

    public enum RedisMode { LIST, PUBSUB }

    public String getSerialPort() {
        return serial.getPort();
    }

    public int getBaudRate() {
        return serial.getBaudRate();
    }

    public String getRedisHost() {
        return redis.getHost();
    }

    public int getRedisPort() {
        return redis.getPort();
    }

    public String getRedisPassword() {
        return redis.getPassword();
    }

    public int getRedisDatabase() {
        return redis.getDatabase();
    }

    public String getRedisQueueName() {
        return redis.getQueueName();
    }

    public RedisMode getRedisMode() {
        return redis.getMode();
    }

    public boolean isDeleteSmsAfterRead() {
        return behavior.isDeleteSmsAfterRead();
    }

    public long getUnreadPollIntervalMs() {
        return behavior.getUnreadPollIntervalMs();
    }

    public int getRedisPublishRetries() {
        return redis.getPublishRetries();
    }

    @Data
    public static class Serial {
        @NotBlank
        private String port;

        @Min(1)
        private int baudRate = 115200;
    }

    @Data
    public static class Redis {
        @NotBlank
        private String host = "127.0.0.1";

        @Min(1)
        private int port = 6379;

        private String password;

        @Min(0)
        private int database = 0;

        @NotBlank
        private String queueName = "sms:incoming";

        private RedisMode mode = RedisMode.LIST;

        @Min(1)
        private int publishRetries = 3;
    }

    @Data
    public static class Behavior {
        private boolean deleteSmsAfterRead = false;

        @Min(1000)
        private long unreadPollIntervalMs = 60_000;
    }
}
