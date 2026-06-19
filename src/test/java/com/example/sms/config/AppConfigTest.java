package com.example.sms.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void verifiesDefaultValues() {
        AppConfig config = new AppConfig();

        // Kiểm tra các giá trị mặc định khi không có biến môi trường nào được set
        assertEquals("COM5", config.getSerialPort());
        assertEquals(115200, config.getBaudRate());

        assertEquals("127.0.0.1", config.getRedisHost());
        assertEquals(6379, config.getRedisPort());
        assertEquals("", config.getRedisPassword());
        assertEquals(0, config.getRedisDatabase());
        assertEquals("sms:incoming", config.getRedisQueueName());
        assertEquals(AppConfig.RedisMode.VALUE, config.getRedisMode());
        assertEquals(3, config.getRedisPublishRetries());

        assertFalse(config.isDeleteSmsAfterRead());
        assertEquals(60000L, config.getUnreadPollIntervalMs());
        assertEquals(100, config.getPollIntervalMs());

        assertEquals("\\+CMTI:\\s*\"[^\"]+\",(\\d+)", config.getSmsIndexCmtiPattern());
        assertEquals("\\+CMGL:\\s*(\\d+),", config.getSmsIndexCmglPattern());
        assertEquals("Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)", config.getSmsOtpPattern());

        assertEquals(20, config.getSimHighWatermark());
        assertEquals(5, config.getSimKeepRecent());

    }
}
