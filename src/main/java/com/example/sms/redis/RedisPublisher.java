package com.example.sms.redis;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.RedisPublishException;
import com.example.sms.smsreader.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gửi {@link SmsMessage} đã parse lên Redis.
 *
 * Thiết kế tối giản sau khi dedup được chuyển về SmsReaderRuntime (in-memory):
 * - Chỉ còn 1 method publish() duy nhất
 * - Bỏ publishIfNewerThanCurrent() — logic dedup không còn thuộc về lớp này
 * - Bỏ WATCH/transaction — không cần vì SmsReaderRuntime đảm bảo single-writer
 * - Bỏ StoredSms, parseStoredSms — không còn cần đọc lại Redis để so sánh
 * - Retry nằm ở caller (SmsReaderRuntime.publishWithRetry) — RedisPublisher
 * chỉ throw exception khi lỗi, không tự retry tránh double-retry
 *
 * Thread safety:
 * - Chỉ được gọi từ commandExecutor (single thread) trong SmsReaderRuntime
 * - Connection Lettuce sync là thread-safe nhưng không cần concurrent access ở
 * đây
 */
public class RedisPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPublisher.class);

    private final AppConfig config;
    private final ObjectMapper mapper;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    public RedisPublisher(AppConfig config) {
        this.config = config;
        this.mapper = buildMapper();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Tạo Redis connection từ config.
     *
     * Được gọi khi khởi động — fail fast nếu Redis không sẵn sàng.
     */
    public void connect() {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(config.getRedisHost())
                .withPort(config.getRedisPort())
                .withDatabase(config.getRedisDatabase())
                .withTimeout(Duration.ofMillis(config.getRedisTimeoutMs()));

        if (config.getRedisPassword() != null && !config.getRedisPassword().isBlank()) {
            uriBuilder.withPassword(config.getRedisPassword().toCharArray());
        }

        redisClient = RedisClient.create(uriBuilder.build());
        connection = redisClient.connect();
        commands = connection.sync();

        log.info("Connected to Redis at {}:{}/db{}.",
                config.getRedisHost(), config.getRedisPort(), config.getRedisDatabase());
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        log.info("Redis connection closed.");
    }

    // -------------------------------------------------------------------------
    // Publish
    // -------------------------------------------------------------------------

    /**
     * Serialize SMS thành JSON và ghi vào Redis theo mode cấu hình.
     *
     * Mode LIST → RPUSH vào list (consumer BLPOP/LRANGE)
     * Mode VALUE → SET key (consumer GET)
     *
     * Dedup KHÔNG nằm ở đây — caller (SmsReaderRuntime) đã lọc duplicate
     * bằng lastPublishedTransactionId trước khi gọi method này.
     *
     * Retry KHÔNG nằm ở đây — caller dùng publishWithRetry() với exponential
     * backoff. RedisPublisher chỉ throw exception khi lỗi để caller quyết định.
     *
     * @throws RedisPublishException nếu ghi Redis thất bại.
     */
    public void publish(SmsMessage message) {
        String json = toJson(message);
        String target = config.getRedisQueueName();

        try {
            if (config.getRedisMode() == AppConfig.RedisMode.LIST) {
                commands.rpush(target, json);
                log.info("RPUSH SMS index={} transactionId={} to list '{}'.",
                        message.getIndex(), message.getTransactionId(), target);
            } else {
                commands.set(target, json);
                log.info("SET SMS index={} transactionId={} to key '{}'.",
                        message.getIndex(), message.getTransactionId(), target);
            }
        } catch (Exception e) {
            throw new RedisPublishException(
                    "Redis publish failed for SMS index=" + message.getIndex()
                            + " transactionId=" + message.getTransactionId(),
                    e);
        }
    }

    /**
     * Kiểm tra kết nối Redis còn sống không.
     *
     * @return "PONG" nếu OK, null nếu chưa connect.
     */
    public String ping() {
        return commands == null ? null : commands.ping();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Serialize SmsMessage thành JSON payload gửi Redis.
     *
     * Chỉ serialize các field cần thiết cho consumer — không serialize
     * toàn bộ object để tránh expose internal field.
     */
    private String toJson(SmsMessage msg) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("index", msg.getIndex());
            payload.put("transactionId", msg.getTransactionId());
            payload.put("otp", msg.getOtp());
            payload.put("timestamp", msg.getTimestamp());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "JSON serialization failed for SMS index=" + msg.getIndex() + ": " + e.getMessage(), e);
        }
    }

    private static ObjectMapper buildMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}