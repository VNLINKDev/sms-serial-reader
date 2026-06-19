package com.example.sms.config;

import lombok.Data;

/**
 * Cấu hình nghiệp vụ của service, được nạp trực tiếp từ các biến môi trường (Environment Variables).
 */
@Data
public class AppConfig {

    private final String serialPort = getEnv("SERIAL_PORT", "COM5");
    private final int baudRate = getEnvInt("BAUD_RATE", 115200);

    private final String redisHost = getEnv("REDIS_HOST", "127.0.0.1");
    private final int redisPort = getEnvInt("REDIS_PORT", 6379);
    private final String redisPassword = getEnv("REDIS_PASSWORD", "");
    private final int redisDatabase = getEnvInt("REDIS_DATABASE", 0);
    private final String redisQueueName = getEnv("REDIS_QUEUE_NAME", "sms:incoming");
    private final RedisMode redisMode = getEnvEnum("REDIS_MODE", RedisMode.class, RedisMode.VALUE);
    private final int redisPublishRetries = getEnvInt("REDIS_PUBLISH_RETRIES", 3);
    /** Key lưu JSON của tin nhắn mới nhất đã publish — dùng để check duplicate thay vì scan list. */
    private final String redisLatestKey = getEnv("REDIS_LATEST_KEY", "sms:latest");

    private final boolean deleteSmsAfterRead = getEnvBoolean("DELETE_SMS_AFTER_READ", false);
    private final long unreadPollIntervalMs = getEnvLong("UNREAD_POLL_INTERVAL_MS", 60000L);
    private final int pollIntervalMs = getEnvInt("POLL_INTERVAL_MS", 100);

    /** Ngưỡng số SMS trên SIM kích hoạt cleanup tự động (mặc định 20/30 slot). */
    private final int simHighWatermark = getEnvInt("SIM_HIGH_WATERMARK", 20);
    /** Số tin nhắn gần nhất giữ lại sau cleanup để phục vụ debug (mặc định 5). */
    private final int simKeepRecent = getEnvInt("SIM_KEEP_RECENT", 5);

    /** Bật/tắt gửi notification lên Telegram khi nhận OTP. */
    private final boolean telegramEnabled = getEnvBoolean("TELEGRAM_ENABLED", false);
    /** Token của Telegram Bot (lấy từ @BotFather). */
    private final String telegramBotToken = getEnv("TELEGRAM_BOT_TOKEN", "");
    /** Chat ID của group/channel Telegram nhận notification. */
    private final String telegramChatId = getEnv("TELEGRAM_CHAT_ID", "");

    private final String smsIndexCmtiPattern = getEnv("SMS_INDEX_CMTI_PATTERN", "\\+CMTI:\\s*\"[^\"]+\",(\\d+)");
    private final String smsIndexCmglPattern = getEnv("SMS_INDEX_CMGL_PATTERN", "\\+CMGL:\\s*(\\d+),");
    private final String smsOtpPattern = getEnv("SMS_OTP_PATTERN", "Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)");

    /**
     * Chế độ ghi Redis:
     * {@code VALUE} lưu trạng thái SMS mới nhất vào một key, phù hợp consumer đọc latest state.
     * {@code LIST} lưu tất cả SMS vào một list, phù hợp consumer cần đọc toàn bộ SMS.
     */
    public enum RedisMode { VALUE, LIST }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }

    private static int getEnvInt(String name, int defaultValue) {
        String value = System.getenv(name);
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
        String value = System.getenv(name);
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
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static <T extends Enum<T>> T getEnvEnum(String name, Class<T> enumClass, T defaultValue) {
        String value = System.getenv(name);
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
