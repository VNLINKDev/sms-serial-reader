package com.example.sms.redis;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.RedisPublishException;
import com.example.sms.smsreader.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.core.type.TypeReference;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Đẩy các đối tượng {@link SmsMessage} đã phân tích sang Redis.
 *
 * <p>Hỗ trợ hai chế độ được điều khiển bởi {@link AppConfig.RedisMode}:
 * <ul>
 *   <li>{@code VALUE} lưu chuỗi JSON mới nhất vào Redis bằng SET.</li>
 *   <li>{@code PUBSUB} phát chuỗi JSON lên kênh Redis bằng PUBLISH.</li>
 * </ul>
 *
 * <p>Các lần gửi thất bại được thử lại tối đa {@code maxRetries} lần với
 * backoff lũy thừa trước khi ném {@link RedisPublishException}.
 */
@Component
@RequiredArgsConstructor
public class RedisPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPublisher.class);

    private static final long INITIAL_BACKOFF_MS = 200;
    private static final int CONDITIONAL_SET_RETRIES = 3;

    private final AppConfig   config;
    private final ObjectMapper mapper = buildMapper();

    private RedisClient                    redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String>  commands;

    // -------------------------------------------------------------------------
    // Vòng đời
    // -------------------------------------------------------------------------

    public void connect() {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(config.getRedisHost())
                .withPort(config.getRedisPort())
                .withDatabase(config.getRedisDatabase())
                .withTimeout(Duration.ofSeconds(10));

        if (config.getRedisPassword() != null && !config.getRedisPassword().isBlank()) {
            uriBuilder.withPassword(config.getRedisPassword().toCharArray());
        }

        redisClient = RedisClient.create(uriBuilder.build());
        connection  = redisClient.connect();
        commands    = connection.sync();

        log.info("Connected to Redis at {}:{}/{}",
                config.getRedisHost(), config.getRedisPort(), config.getRedisDatabase());
    }

    @PostConstruct
    void init() {
        connect();
    }

    @Override
    @PreDestroy
    public void close() {
        if (connection != null) connection.close();
        if (redisClient != null) redisClient.shutdown();
        log.info("Redis connection closed.");
    }

    // -------------------------------------------------------------------------
    // Gửi dữ liệu
    // -------------------------------------------------------------------------

    /**
     * Chuyển {@code message} thành JSON rồi ghi hoặc phát sang Redis.
     *
     * @throws RedisPublishException nếu tất cả lần thử đều thất bại.
     */
    public void publish(SmsMessage message) {
        String json = toJson(message);
        String target = config.getRedisQueueName();
        int maxRetries = config.getRedisPublishRetries();

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (config.getRedisMode() == AppConfig.RedisMode.PUBSUB) {
                    commands.publish(target, json);
                    log.info("Published SMS index={} to channel '{}'.", message.getIndex(), target);
                } else {
                    commands.set(target, json);
                    log.info("Set SMS index={} to key '{}'.", message.getIndex(), target);
                }
                return;   // thành công

            } catch (Exception e) {
                lastException = e;
                log.warn("Redis publish failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    sleep(INITIAL_BACKOFF_MS * (1L << (attempt - 1)));   // backoff lũy thừa
                }
            }
        }

        throw new RedisPublishException(
                "Failed to publish SMS index=" + message.getIndex()
                + " after " + maxRetries + " attempts.", lastException);
    }

    /**
     * Chỉ lưu SMS quét theo lịch nếu nó mới hơn giá trị hiện tại trong Redis.
     */
    public boolean publishIfNewerThanCurrent(SmsMessage candidate) {
        if (config.getRedisMode() == AppConfig.RedisMode.PUBSUB) {
            log.warn("Skipping conditional Redis save for SMS index={} because PUBSUB mode has no stored latest value.",
                    candidate.getIndex());
            return false;
        }

        String target = config.getRedisQueueName();
        String json = toJson(candidate);
        Exception lastException = null;

        for (int attempt = 1; attempt <= CONDITIONAL_SET_RETRIES; attempt++) {
            try {
                commands.watch(target);

                String currentJson = commands.get(target);
                Optional<StoredSms> current = parseStoredSms(currentJson);
                if (current.isPresent() && !isNewer(candidate, current.get())) {
                    commands.unwatch();
                    log.info("Skipped scheduled SMS index={} because Redis already has newer/equal SMS index={}.",
                            candidate.getIndex(), current.get().index());
                    return false;
                }

                commands.multi();
                commands.set(target, json);
                TransactionResult result = commands.exec();
                if (!result.wasDiscarded()) {
                    log.info("Set scheduled SMS index={} to key '{}'.", candidate.getIndex(), target);
                    return true;
                }

                log.info("Retrying scheduled SMS index={} because Redis key '{}' changed during compare.",
                        candidate.getIndex(), target);

            } catch (Exception e) {
                lastException = e;
                log.warn("Conditional Redis save failed for scheduled SMS index={} (attempt {}/{}): {}",
                        candidate.getIndex(), attempt, CONDITIONAL_SET_RETRIES, e.getMessage());
                safeUnwatch();
            }
        }

        throw new RedisPublishException(
                "Failed conditional publish for scheduled SMS index=" + candidate.getIndex()
                        + " after " + CONDITIONAL_SET_RETRIES + " attempts.", lastException);
    }

    public String ping() {
        return commands == null ? null : commands.ping();
    }

    // -------------------------------------------------------------------------
    // Hàm hỗ trợ
    // -------------------------------------------------------------------------

    private String toJson(SmsMessage msg) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("index",      msg.getIndex());
            payload.put("transactionId",     msg.getTransactionId());
            payload.put("otp",        msg.getOtp());
            payload.put("timestamp",  msg.getTimestamp());

            return mapper.writeValueAsString(payload);

        } catch (Exception e) {
            throw new IllegalStateException("JSON serialisation failed: " + e.getMessage(), e);
        }
    }

    private Optional<StoredSms> parseStoredSms(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = mapper.readValue(json, new TypeReference<>() {});
            Object timestampValue = payload.get("timestamp");
            if (timestampValue == null) {
                return Optional.empty();
            }

            int index = parseIndex(payload.get("index"));
            OffsetDateTime timestamp = OffsetDateTime.parse(timestampValue.toString());
            return Optional.of(new StoredSms(index, timestamp));

        } catch (RuntimeException e) {
            log.warn("Could not parse current Redis SMS payload; scheduled SMS will be allowed to overwrite it: {}",
                    e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read current Redis SMS payload; scheduled SMS will be allowed to overwrite it: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    private static int parseIndex(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value.toString());
    }

    private static boolean isNewer(SmsMessage candidate, StoredSms current) {
        int timestampCompare = candidate.getTimestamp().toInstant().compareTo(current.timestamp().toInstant());
        return timestampCompare > 0
                || (timestampCompare == 0 && candidate.getIndex() > current.index());
    }

    private void safeUnwatch() {
        try {
            commands.unwatch();
        } catch (Exception ignored) {
            // Cố gắng dọn dẹp trước lần thử tiếp theo.
        }
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private record StoredSms(int index, OffsetDateTime timestamp) {
    }
}
