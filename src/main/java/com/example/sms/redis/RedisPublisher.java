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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Integration adapter chịu trách nhiệm chuyển {@link SmsMessage} đã parse thành
 * JSON và đưa sang Redis.
 *
 * Service hỗ trợ hai kiểu delivery:
 *
 * {@code VALUE}: ghi SMS mới nhất vào một Redis key bằng SET.
 * {@code LIST}: lưu tất cả SMS vào một list, phù hợp với consumer cần đọc toàn bộ SMS.
 *
 * Ngoài queue chính ({@code redisQueueName}), service luôn duy trì một key phụ
 * ({@code redisLatestKey}) lưu JSON của tin nhắn mới nhất đã publish.
 * Key này được dùng để kiểm tra duplicate trong flow recovery thay vì scan vào list,
 * giúp tách biệt logic check khỏi cấu trúc dữ liệu của queue chính.
 */
@RequiredArgsConstructor
public class RedisPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPublisher.class);

    private static final long INITIAL_BACKOFF_MS = 200;

    private final AppConfig config;
    private final ObjectMapper mapper = buildMapper();

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    // -------------------------------------------------------------------------
    // Vòng đời Redis connection
    // -------------------------------------------------------------------------

    /**
     * Tạo Redis client/connection từ cấu hình runtime.
     *
     * Timeout kết nối được đặt hữu hạn để startup hoặc reconnect lỗi không
     * treo vô thời hạn. Nếu Redis không sẵn sàng ở startup, ứng dụng fail fast
     * thay vì chạy nhưng không publish được SMS.
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

        log.info("Connected to Redis at {}:{}/{}",
                config.getRedisHost(), config.getRedisPort(), config.getRedisDatabase());
    }

    /**
     * Đóng connection trước khi bean bị destroy.
     */
    @Override
    public void close() {
        if (connection != null)
            connection.close();
        if (redisClient != null)
            redisClient.shutdown();
        log.info("Redis connection closed.");
    }

    // -------------------------------------------------------------------------
    // Gửi dữ liệu
    // -------------------------------------------------------------------------

    /**
     * Publish SMS theo mode cấu hình.
     *
     * Flow realtime ưu tiên độ đơn giản: serialize payload, gửi Redis, retry
     * với backoff lũy thừa nếu lỗi tạm thời. Sau khi hết retry, exception được
     * ném để runtime log lỗi theo index SMS, giúp vận hành truy vết message bị lỗi.
     *
     * @throws RedisPublishException nếu tất cả lần thử đều thất bại.
     */
    public void publish(SmsMessage message) {
        String json = toJson(message);
        String target = config.getRedisQueueName();
        String latestKey = config.getRedisLatestKey();
        int maxRetries = config.getRedisPublishRetries();

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (config.getRedisMode() == AppConfig.RedisMode.LIST) {
                    commands.rpush(target, json);
                    log.info("Pushed SMS index={} to list '{}'.", message.getIndex(), target);
                } else {
                    commands.set(target, json);
                    log.info("Set SMS index={} to key '{}'.", message.getIndex(), target);
                }
                // Luôn cập nhật key latest để flow recovery có thể check chính xác.
                commands.set(latestKey, json);
                log.debug("Updated latest-key '{}' for SMS index={}.", latestKey, message.getIndex());
                return; // thành công

            } catch (Exception e) {
                lastException = e;
                log.warn("Redis publish failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    sleep(INITIAL_BACKOFF_MS * (1L << (attempt - 1))); // backoff lũy thừa
                }
            }
        }

        throw new RedisPublishException(
                "Failed to publish SMS index=" + message.getIndex()
                        + " after " + maxRetries + " attempts.",
                lastException);
    }

    /**
     * Ghi SMS từ flow recovery chỉ khi dữ liệu mới hơn state hiện tại.
     *
     * So sánh dựa trên {@code redisLatestKey} — key value riêng lưu JSON của tin
     * nhắn mới nhất đã publish — thay vì đọc từ queue chính (list/value). Nhờ đó
     * logic check hoàn toàn tách khỏi cấu trúc dữ liệu của queue chính, đồng thời
     * đơn giản hơn vì latestKey luôn là dạng string (không cần lindex).
     *
     * WATCH được đặt trên {@code latestKey}: nếu flow realtime cập nhật key này
     * trong lúc scheduler đang so sánh, EXEC bị discard và method retry.
     *
     * Khi commit, transaction ghi đồng thời vào queue chính (rpush/set) và
     * cập nhật {@code latestKey} để các lần check sau có state mới nhất.
     *
     * NOTE: Transaction dùng connection singleton — xem lưu ý concurrency ở {@link #publish}.
     *
     * Edge case: nếu latestKey không parse được, cho phép overwrite để tự phục hồi.
     */
    public boolean publishIfNewerThanCurrent(SmsMessage candidate) {
        String target = config.getRedisQueueName();
        String latestKey = config.getRedisLatestKey();
        String json = toJson(candidate);
        boolean isListMode = (config.getRedisMode() == AppConfig.RedisMode.LIST);

        try {
            // Đọc từ latestKey (luôn là value) thay vì scan vào queue chính.
            String currentJson = commands.get(latestKey);
            Optional<StoredSms> current = parseStoredSms(currentJson);
            if (current.isPresent() && !isNewer(candidate, current.get())) {
                log.info("Skipped scheduled SMS index={} because latest-key '{}' already has newer/equal SMS index={}.",
                        candidate.getIndex(), latestKey, current.get().index());
                return false;
            }

            // Ghi vào queue chính.
            if (isListMode) {
                commands.rpush(target, json);
            } else {
                commands.set(target, json);
            }
            // Đồng thời cập nhật latestKey để check lần sau chính xác.
            commands.set(latestKey, json);

            if (isListMode) {
                log.info("Pushed scheduled SMS index={} to list '{}' and updated latest-key '{}'.",
                        candidate.getIndex(), target, latestKey);
            } else {
                log.info("Set scheduled SMS index={} to key '{}' and updated latest-key '{}'.",
                        candidate.getIndex(), target, latestKey);
            }
            return true;

        } catch (Exception e) {
            log.error("Conditional Redis save failed for scheduled SMS index={}: {}",
                    candidate.getIndex(), e.getMessage(), e);
            throw new RedisPublishException(
                    "Failed conditional publish for scheduled SMS index=" + candidate.getIndex(), e);
        }
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
            payload.put("index", msg.getIndex());
            payload.put("transactionId", msg.getTransactionId());
            payload.put("otp", msg.getOtp());
            payload.put("timestamp", msg.getTimestamp());

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
            Map<String, Object> payload = mapper.readValue(json, new TypeReference<>() {
            });
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
        if (value instanceof Number) {
            return ((Number) value).intValue();
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

    private static ObjectMapper buildMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StoredSms {
        private final int index;
        private final OffsetDateTime timestamp;

        StoredSms(int index, OffsetDateTime timestamp) {
            this.index = index;
            this.timestamp = timestamp;
        }

        int index() { return index; }
        OffsetDateTime timestamp() { return timestamp; }
    }
}
