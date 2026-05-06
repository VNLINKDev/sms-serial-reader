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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes parsed {@link SmsMessage} objects to Redis.
 *
 * <p>Supports two modes controlled by {@link AppConfig.RedisMode}:
 * <ul>
 *   <li>{@code LIST} pushes a JSON string to the tail of a Redis list via RPUSH.</li>
 *   <li>{@code PUBSUB} publishes a JSON string to a Redis channel via PUBLISH.</li>
 * </ul>
 *
 * <p>Failed publish attempts are retried up to {@code maxRetries} times with
 * exponential back-off before throwing {@link RedisPublishException}.
 */
@Component
public class RedisPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPublisher.class);

    private static final long INITIAL_BACKOFF_MS = 200;

    private final AppConfig   config;
    private final ObjectMapper mapper;
    private final int          maxRetries;

    private RedisClient                    redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String>  commands;

    public RedisPublisher(AppConfig config) {
        this.config     = config;
        this.maxRetries = config.getRedisPublishRetries();
        this.mapper     = buildMapper();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
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
    // Publishing
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code message} to JSON and pushes/publishes it to Redis.
     *
     * @throws RedisPublishException if all retry attempts fail.
     */
    public void publish(SmsMessage message) {
        String json = toJson(message);
        String target = config.getRedisQueueName();

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
                return;   // success

            } catch (Exception e) {
                lastException = e;
                log.warn("Redis publish failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    sleep(INITIAL_BACKOFF_MS * (1L << (attempt - 1)));   // exponential backoff
                }
            }
        }

        throw new RedisPublishException(
                "Failed to publish SMS index=" + message.getIndex()
                + " after " + maxRetries + " attempts.", lastException);
    }

    // -------------------------------------------------------------------------
    // Helpers
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

    private static ObjectMapper buildMapper() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return om;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
