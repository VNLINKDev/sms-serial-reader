package com.example.sms.smsreader;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.NonOtpSmsException;
import com.example.sms.exception.SerialPortException;
import com.example.sms.serial.AtCommandClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.startsWith;

@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock
    private AtCommandClient atClient;

    @Mock
    private SmsParser smsParser;

    @Mock
    private AppConfig config;

    private SmsService smsService;

    @BeforeEach
    void setUp() {
        // Set up default behavior for AppConfig index pattern
        lenient().when(config.getSmsIndexCmglPattern()).thenReturn("\\+CMGL:\\s*(\\d+),");
        lenient().when(atClient.sendAndWait(startsWith("AT+CMGD="))).thenReturn("OK");
        smsService = new SmsService(atClient, smsParser, config);
    }

    @Test
    void testReadAndParseSuccessWithoutDelete() {
        int index = 1;
        String rawResponse = "raw response";
        SmsMessage message = new SmsMessage(index, "TX123", "9876", OffsetDateTime.now());

        when(config.isDeleteSmsAfterRead()).thenReturn(false);
        when(atClient.sendAndWait("AT+CMGR=" + index)).thenReturn(rawResponse);
        when(smsParser.parse(index, rawResponse)).thenReturn(message);

        Optional<SmsMessage> result = smsService.readAndParse(index);

        assertTrue(result.isPresent());
        assertEquals(message, result.get());
        verify(atClient, never()).sendAndWait("AT+CMGD=" + index);
    }

    @Test
    void testReadAndParseSuccessWithDelete() {
        int index = 2;
        String rawResponse = "raw response";
        SmsMessage message = new SmsMessage(index, "TX456", "1122", OffsetDateTime.now());

        when(config.isDeleteSmsAfterRead()).thenReturn(true);
        when(atClient.sendAndWait("AT+CMGR=" + index)).thenReturn(rawResponse);
        when(smsParser.parse(index, rawResponse)).thenReturn(message);

        Optional<SmsMessage> result = smsService.readAndParse(index);

        assertTrue(result.isPresent());
        assertEquals(message, result.get());
        verify(atClient, times(1)).sendAndWait("AT+CMGD=" + index);
    }

    @Test
    void testReadAndParseNonOtpSmsShouldDelete() {
        int index = 3;
        String rawResponse = "raw response";

        when(atClient.sendAndWait("AT+CMGR=" + index)).thenReturn(rawResponse);
        when(smsParser.parse(index, rawResponse)).thenThrow(new NonOtpSmsException("No OTP"));

        Optional<SmsMessage> result = smsService.readAndParse(index);

        assertFalse(result.isPresent());
        // Verify delete is called even if deleteSmsAfterRead is false, because it's non-OTP
        verify(atClient, times(1)).sendAndWait("AT+CMGD=" + index);
    }

    @Test
    void testReadAndParseGeneralExceptionReturnsEmpty() {
        int index = 4;
        when(atClient.sendAndWait("AT+CMGR=" + index)).thenThrow(new RuntimeException("IO error"));

        Optional<SmsMessage> result = smsService.readAndParse(index);

        assertFalse(result.isPresent());
        verify(atClient, never()).sendAndWait("AT+CMGD=" + index);
    }

    @Test
    void testListAll() {
        when(atClient.sendAndWait("AT+CMGL=\"ALL\"")).thenReturn("list of sms");
        String result = smsService.listAll();
        assertEquals("list of sms", result);
    }

    @Test
    void testListAllIndexes() {
        String cmglResponse = "+CMGL: 1,\"REC UNREAD\",\"+8490\"\r\n" +
                "+CMGL: 5,\"REC READ\",\"+8490\"\r\n" +
                "+CMGL: 12,\"REC READ\",\"+8490\"\r\n";

        when(atClient.sendAndWait("AT+CMGL=\"ALL\"")).thenReturn(cmglResponse);

        List<Integer> indexes = smsService.listAllIndexes();

        assertEquals(3, indexes.size());
        assertEquals(Arrays.asList(1, 5, 12), indexes);
    }

    @Test
    void testReadAndParseAllSortsByTimestampAndDeletesNonOtp() {
        String cmglResponse = "+CMGL: 2,...\r\n+CMGL: 4,...\r\n+CMGL: 6,...\r\n";
        when(atClient.sendAndWait("AT+CMGL=\"ALL\"")).thenReturn(cmglResponse);

        when(atClient.sendAndWait("AT+CMGR=2")).thenReturn("res2");
        when(atClient.sendAndWait("AT+CMGR=4")).thenReturn("res4");
        when(atClient.sendAndWait("AT+CMGR=6")).thenReturn("res6");

        SmsMessage msg2 = new SmsMessage(2, "TX2", "0002", OffsetDateTime.of(2026, 6, 24, 12, 0, 0, 0, ZoneOffset.UTC));
        SmsMessage msg6 = new SmsMessage(6, "TX6", "0006", OffsetDateTime.of(2026, 6, 24, 10, 0, 0, 0, ZoneOffset.UTC)); // Oldest

        when(smsParser.parse(2, "res2")).thenReturn(msg2);
        when(smsParser.parse(4, "res4")).thenThrow(new NonOtpSmsException("not otp")); // non-OTP should trigger delete
        when(smsParser.parse(6, "res6")).thenReturn(msg6);

        List<SmsMessage> results = smsService.readAndParseAll();

        // Sorted by timestamp (msg6 first, then msg2)
        assertEquals(2, results.size());
        assertEquals(msg6, results.get(0));
        assertEquals(msg2, results.get(1));

        // Verify index 4 was deleted
        verify(atClient, times(1)).sendAndWait("AT+CMGD=4");
        // Verify index 2 and 6 were not deleted in the read phase
        verify(atClient, never()).sendAndWait("AT+CMGD=2");
        verify(atClient, never()).sendAndWait("AT+CMGD=6");
    }

    @Test
    void testReadAndParseAllPropagatesModemTimeoutException() {
        when(atClient.sendAndWait("AT+CMGL=\"ALL\"")).thenReturn("+CMGL: 1,...\r\n");
        when(atClient.sendAndWait("AT+CMGR=1")).thenThrow(new ModemTimeoutException("AT+CMGR=1", 1000));

        assertThrows(ModemTimeoutException.class, () -> {
            smsService.readAndParseAll();
        });
    }

    @Test
    void testCleanupOldSmsBelowWatermarkDoesNothing() {
        when(config.getSimHighWatermark()).thenReturn(5);
        when(config.getSimKeepRecent()).thenReturn(2);

        List<SmsMessage> messages = Arrays.asList(
                new SmsMessage(1, "T1", "1", OffsetDateTime.now()),
                new SmsMessage(2, "T2", "2", OffsetDateTime.now())
        );

        int deleted = smsService.cleanupOldSms(messages);

        assertEquals(0, deleted);
        verify(atClient, never()).sendAndWait(anyString());
    }

    @Test
    void testCleanupOldSmsExceedsWatermarkDeletesOldest() {
        when(config.getSimHighWatermark()).thenReturn(3);
        when(config.getSimKeepRecent()).thenReturn(1);

        OffsetDateTime now = OffsetDateTime.now();
        SmsMessage msg1 = new SmsMessage(10, "T1", "1", now.minusMinutes(10)); // Oldest
        SmsMessage msg2 = new SmsMessage(11, "T2", "2", now.minusMinutes(5));
        SmsMessage msg3 = new SmsMessage(12, "T3", "3", now);                 // Newest

        // Already sorted by timestamp in the list (cleanupOldSms expects it sorted)
        List<SmsMessage> messages = Arrays.asList(msg1, msg2, msg3);

        int deleted = smsService.cleanupOldSms(messages);

        // Watermark is 3, total is 3 (so >= watermark). Keep 1 recent (msg3). Delete msg1 and msg2.
        assertEquals(2, deleted);
        verify(atClient, times(1)).sendAndWait("AT+CMGD=10");
        verify(atClient, times(1)).sendAndWait("AT+CMGD=11");
        verify(atClient, never()).sendAndWait("AT+CMGD=12");
    }
}
