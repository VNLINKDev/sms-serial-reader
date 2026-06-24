package com.example.sms.redis;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.RedisPublishException;
import com.example.sms.smsreader.SmsMessage;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisPublisherTest {

    @Mock
    private AppConfig config;

    @Mock
    private RedisCommands<String, String> commands;

    @Mock
    private StatefulRedisConnection<String, String> connection;

    @Mock
    private RedisClient redisClient;

    @InjectMocks
    private RedisPublisher redisPublisher;

    @BeforeEach
    void setUp() throws Exception {
        // Manually inject connection and redisClient if needed using reflection since they are private
        setPrivateField(redisPublisher, "connection", connection);
        setPrivateField(redisPublisher, "commands", commands);
        setPrivateField(redisPublisher, "redisClient", redisClient);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testPublishInValueMode() {
        SmsMessage message = new SmsMessage(
                1, 
                "TX100", 
                "123456", 
                OffsetDateTime.of(2026, 6, 24, 12, 0, 0, 0, ZoneOffset.UTC)
        );

        when(config.getRedisQueueName()).thenReturn("sms:latest");
        when(config.getRedisMode()).thenReturn(AppConfig.RedisMode.VALUE);

        redisPublisher.publish(message);

        // Verify commands.set is called with serialized JSON
        // The timestamp field is serialized with jackson-datatype-jsr310
        // Expected format: "timestamp":"2026-06-24T12:00:00Z"
        verify(commands, times(1)).set(
                eq("sms:latest"), 
                contains("\"transactionId\":\"TX100\"")
        );
        verify(commands, times(1)).set(
                eq("sms:latest"), 
                contains("\"otp\":\"123456\"")
        );
        verify(commands, times(1)).set(
                eq("sms:latest"), 
                contains("\"timestamp\":\"2026-06-24T12:00:00Z\"")
        );
        verify(commands, never()).rpush(anyString(), anyString());
    }

    @Test
    void testPublishInListMode() {
        SmsMessage message = new SmsMessage(
                2, 
                "TX200", 
                "654321", 
                OffsetDateTime.of(2026, 6, 24, 12, 0, 0, 0, ZoneOffset.UTC)
        );

        when(config.getRedisQueueName()).thenReturn("sms:incoming");
        when(config.getRedisMode()).thenReturn(AppConfig.RedisMode.LIST);

        redisPublisher.publish(message);

        // Verify commands.rpush is called
        verify(commands, times(1)).rpush(
                eq("sms:incoming"), 
                contains("\"transactionId\":\"TX200\"")
        );
        verify(commands, never()).set(anyString(), anyString());
    }

    @Test
    void testPublishThrowsRedisPublishException() {
        SmsMessage message = new SmsMessage(
                3, 
                "TX300", 
                "111111", 
                OffsetDateTime.now()
        );

        when(config.getRedisQueueName()).thenReturn("sms:incoming");
        when(config.getRedisMode()).thenReturn(AppConfig.RedisMode.LIST);
        
        // Mock commands to throw exception
        when(commands.rpush(anyString(), anyString())).thenThrow(new RuntimeException("Redis connection lost"));

        assertThrows(RedisPublishException.class, () -> {
            redisPublisher.publish(message);
        });
    }

    @Test
    void testPingSuccess() {
        when(commands.ping()).thenReturn("PONG");
        String pingResult = redisPublisher.ping();
        assertEquals("PONG", pingResult);
    }

    @Test
    void testPingNullWhenNotConnected() throws Exception {
        setPrivateField(redisPublisher, "commands", null);
        String pingResult = redisPublisher.ping();
        assertNull(pingResult);
    }

    @Test
    void testCloseClosesConnectionAndClient() {
        redisPublisher.close();

        verify(connection, times(1)).close();
        verify(redisClient, times(1)).shutdown();
    }
}
