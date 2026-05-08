package com.example.sms.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình nghiệp vụ của service, được bind từ prefix {@code sms.*}.
 *
 */
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

    /**
     * Chế độ ghi Redis:
     * {@code VALUE} lưu trạng thái SMS mới nhất vào một key, phù hợp consumer đọc latest state.
     * {@code LIST} lưu tất cả SMS vào một list, phù hợp consumer cần đọc toàn bộ SMS.
     */
    public enum RedisMode { VALUE, LIST }

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

    /** Cấu hình kết nối serial tới modem GSM. */
    @Data
    public static class Serial {
        @NotBlank
        private String port;

        @Min(1)
        private int baudRate = 115200;
    }

    /** Cấu hình Redis và cách publish payload SMS đã parse. */
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

        private RedisMode mode = RedisMode.VALUE;

        @Min(1)
        private int publishRetries = 3;
    }

    /** Cấu hình hành vi xử lý SMS sau khi đọc và lịch recovery. */
    @Data
    public static class Behavior {
        private boolean deleteSmsAfterRead = false;

        @Min(1000)
        private long unreadPollIntervalMs = 60_000;
    }
}
