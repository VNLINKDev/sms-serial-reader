package com.example.sms.config;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cấu hình nghiệp vụ của service.
 *
 * Thứ tự ưu tiên:
 * 1. Environment Variables
 * 2. File .env cùng thư mục với jar
 * 3. Default value
 */
@Data
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final String serialPort = getEnv("SERIAL_PORT", "COM5");
    private final int baudRate = getEnvInt("BAUD_RATE", 115200);

    private final String redisHost = getEnv("REDIS_HOST", "127.0.0.1");
    private final int redisPort = getEnvInt("REDIS_PORT", 6379);
    private final String redisPassword = getEnv("REDIS_PASSWORD", "");
    private final int redisDatabase = getEnvInt("REDIS_DATABASE", 0);
    private final String redisQueueName = getEnv("REDIS_QUEUE_NAME", "sms:incoming");
    private final RedisMode redisMode = getEnvEnum("REDIS_MODE", RedisMode.class, RedisMode.VALUE);
    private final int redisPublishRetries = getEnvInt("REDIS_PUBLISH_RETRIES", 3);
    private final long redisTimeoutMs = getEnvLong("REDIS_TIMEOUT_MS", 10000L);

    /**
     * Key lưu JSON của tin nhắn mới nhất đã publish.
     */
    private final String redisLatestKey = getEnv("REDIS_LATEST_KEY", "sms:latest");

    private final boolean deleteSmsAfterRead = getEnvBoolean("DELETE_SMS_AFTER_READ", false);
    private final long unreadPollIntervalMs = getEnvLong("UNREAD_POLL_INTERVAL_MS", 60000L);

    /**
     * Ngưỡng số SMS trên SIM kích hoạt cleanup tự động.
     */
    private final int simHighWatermark = getEnvInt("SIM_HIGH_WATERMARK", 20);

    /**
     * Số tin nhắn gần nhất giữ lại sau cleanup.
     */
    private final int simKeepRecent = getEnvInt("SIM_KEEP_RECENT", 5);

    /**
     * Bật/tắt Telegram notification.
     */
    private final boolean telegramEnabled = getEnvBoolean("TELEGRAM_ENABLED", false);

    /**
     * Telegram Bot Token.
     */
    private final String telegramBotToken = getEnv("TELEGRAM_BOT_TOKEN", "");

    /**
     * Telegram Chat ID.
     */
    private final String telegramChatId = getEnv("TELEGRAM_CHAT_ID", "");

    private final String smsIndexCmglPattern = getEnv("SMS_INDEX_CMGL_PATTERN", "\\+CMGL:\\s*(\\d+),");

    private final String smsOtpPattern = getEnv(
            "SMS_OTP_PATTERN",
            "Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)");

    public AppConfig() {
        log.info("=== Cấu hình ứng dụng ===");
        log.info("SERIAL_PORT: {}", serialPort);
        log.info("BAUD_RATE: {}", baudRate);
        log.info("REDIS_HOST: {}", redisHost);
        log.info("REDIS_PORT: {}", redisPort);
        log.info("REDIS_PASSWORD: {}", (redisPassword == null || redisPassword.isBlank()) ? "chưa cấu hình" : "********");
        log.info("REDIS_DATABASE: {}", redisDatabase);
        log.info("REDIS_QUEUE_NAME: {}", redisQueueName);
        log.info("REDIS_MODE: {}", redisMode);
        log.info("REDIS_PUBLISH_RETRIES: {}", redisPublishRetries);
        log.info("REDIS_TIMEOUT_MS: {}", redisTimeoutMs);
        log.info("REDIS_LATEST_KEY: {}", redisLatestKey);
        log.info("DELETE_SMS_AFTER_READ: {}", deleteSmsAfterRead);
        log.info("UNREAD_POLL_INTERVAL_MS: {}", unreadPollIntervalMs);
        log.info("SIM_HIGH_WATERMARK: {}", simHighWatermark);
        log.info("SIM_KEEP_RECENT: {}", simKeepRecent);
        log.info("TELEGRAM_ENABLED: {}", telegramEnabled);
        log.info("TELEGRAM_BOT_TOKEN: {}",
                (telegramBotToken == null || telegramBotToken.isBlank()) ? "chưa cấu hình" : "********");
        log.info("TELEGRAM_CHAT_ID: {}",
                (telegramChatId == null || telegramChatId.isBlank()) ? "chưa cấu hình" : telegramChatId);
        log.info("SMS_INDEX_CMGL_PATTERN: {}", smsIndexCmglPattern);
        log.info("SMS_OTP_PATTERN: {}", smsOtpPattern);
        log.info("==================================");
    }

    /**
     * Chế độ ghi Redis.
     */
    public enum RedisMode {
        VALUE,
        LIST
    }

    private static String getEnv(String name, String defaultValue) {
        String value = EnvLoader.get(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value.trim();
    }

    private static int getEnvInt(String name, int defaultValue) {
        String value = EnvLoader.get(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getEnvLong(String name, long defaultValue) {
        String value = EnvLoader.get(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getEnvBoolean(String name, boolean defaultValue) {
        String value = EnvLoader.get(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private static <T extends Enum<T>> T getEnvEnum(
            String name,
            Class<T> enumClass,
            T defaultValue) {
        String value = EnvLoader.get(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
