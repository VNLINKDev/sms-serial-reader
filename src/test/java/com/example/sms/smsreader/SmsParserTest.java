package com.example.sms.smsreader;

import com.example.sms.exception.NonOtpSmsException;
import com.example.sms.exception.SmsParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SmsParserTest {

    private SmsParser parser;
    private final String otpPatternRegex = "Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)";

    @BeforeEach
    void setUp() {
        parser = new SmsParser(otpPatternRegex);
    }

    @Test
    void testParseValidSmsWithTimezonePlus() {
        String rawResponse = "\r\n+CMGR: \"REC UNREAD\",\"+84909123456\",,\"26/06/24,14:30:00+28\"\r\n" +
                "Ma giao dich 123456 OTP: 987654\r\n" +
                "\r\n" +
                "OK\r\n";

        SmsMessage msg = parser.parse(1, rawResponse);

        assertNotNull(msg);
        assertEquals(1, msg.getIndex());
        assertEquals("123456", msg.getTransactionId());
        assertEquals("987654", msg.getOtp());
        
        // Timezone is +28 quarter-hours: 28 * 15 min = 420 min = +7 hours
        OffsetDateTime expectedTime = OffsetDateTime.of(2026, 6, 24, 14, 30, 0, 0, ZoneOffset.ofHours(7));
        assertEquals(expectedTime, msg.getTimestamp());
    }

    @Test
    void testParseValidSmsWithTimezoneMinus() {
        String rawResponse = "\r\n+CMGR: \"REC READ\",\"+1234567890\",,\"26/06/24,08:15:30-20\"\r\n" +
                "Ma giao dich 789012. Ma OTP: 112233.\r\n" +
                "\r\n" +
                "OK\r\n";

        SmsMessage msg = parser.parse(5, rawResponse);

        assertNotNull(msg);
        assertEquals(5, msg.getIndex());
        assertEquals("789012", msg.getTransactionId());
        assertEquals("112233", msg.getOtp());

        // Timezone is -20 quarter-hours: -20 * 15 min = -300 min = -5 hours
        OffsetDateTime expectedTime = OffsetDateTime.of(2026, 6, 24, 8, 15, 30, 0, ZoneOffset.ofHours(-5));
        assertEquals(expectedTime, msg.getTimestamp());
    }

    @Test
    void testParseNonOtpSmsThrowsException() {
        String rawResponse = "\r\n+CMGR: \"REC UNREAD\",\"+84909123456\",,\"26/06/24,14:30:00+28\"\r\n" +
                "Hello, this is a plain text message without OTP.\r\n" +
                "\r\n" +
                "OK\r\n";

        NonOtpSmsException exception = assertThrows(NonOtpSmsException.class, () -> {
            parser.parse(2, rawResponse);
        });

        assertTrue(exception.getMessage().contains("Không thể trích xuất OTP"));
    }

    @Test
    void testParseNoCmgrHeaderThrowsException() {
        String rawResponse = "\r\nOK\r\n";

        SmsParseException exception = assertThrows(SmsParseException.class, () -> {
            parser.parse(3, rawResponse);
        });

        assertEquals("Không tìm thấy header +CMGR", exception.getMessage());
    }

    @Test
    void testParseInvalidCmgrHeaderThrowsException() {
        String rawResponse = "\r\n+CMGR: invalid,header,format\r\n" +
                "Ma giao dich 123456 OTP: 987654\r\n" +
                "\r\n" +
                "OK\r\n";

        SmsParseException exception = assertThrows(SmsParseException.class, () -> {
            parser.parse(4, rawResponse);
        });

        assertEquals("Header CMGR không hợp lệ", exception.getMessage());
    }

    @Test
    void testParseFallbackTimestamp() {
        // Raw response has empty/invalid timestamp
        String rawResponse = "\r\n+CMGR: \"REC UNREAD\",\"+84909123456\",,\"\"\r\n" +
                "Ma giao dich 123456 OTP: 987654\r\n" +
                "\r\n" +
                "OK\r\n";

        long beforeParse = System.currentTimeMillis();
        SmsMessage msg = parser.parse(10, rawResponse);
        long afterParse = System.currentTimeMillis();

        assertNotNull(msg);
        assertEquals(10, msg.getIndex());
        assertEquals("123456", msg.getTransactionId());
        assertEquals("987654", msg.getOtp());
        
        assertNotNull(msg.getTimestamp());
        long msgEpochMs = msg.getTimestamp().toInstant().toEpochMilli();
        assertTrue(msgEpochMs >= beforeParse && msgEpochMs <= afterParse);
    }
}
