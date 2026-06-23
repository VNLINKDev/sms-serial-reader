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

    @Test
    void verifiesRegexMatching() {
        AppConfig config = new AppConfig();

        // 1. CMTI Pattern
        java.util.regex.Pattern cmti = java.util.regex.Pattern.compile(config.getSmsIndexCmtiPattern());
        java.util.regex.Matcher cmtiMatcher = cmti.matcher("+CMTI: \"SM\",12");
        org.junit.jupiter.api.Assertions.assertTrue(cmtiMatcher.find());
        assertEquals("12", cmtiMatcher.group(1));

        // 2. CMGL Pattern
        java.util.regex.Pattern cmgl = java.util.regex.Pattern.compile(config.getSmsIndexCmglPattern());
        java.util.regex.Matcher cmglMatcher = cmgl.matcher("+CMGL: 15,");
        org.junit.jupiter.api.Assertions.assertTrue(cmglMatcher.find());
        assertEquals("15", cmglMatcher.group(1));

        // 3. OTP Pattern
        java.util.regex.Pattern otp = java.util.regex.Pattern.compile(config.getSmsOtpPattern(), java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher otpMatcher = otp.matcher("Ma giao dich 987654321 OTP : 123456");
        org.junit.jupiter.api.Assertions.assertTrue(otpMatcher.find());
        assertEquals("987654321", otpMatcher.group(1));
        assertEquals("123456", otpMatcher.group(2));
    }
}
