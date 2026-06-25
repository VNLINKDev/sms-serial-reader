package com.example.sms.telegram;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotifierTest {

    @Mock
    private AppConfig config;

    @Mock
    private TelegramNotifier.HttpSender httpSender;

    private TelegramNotifier telegramNotifier;

    @BeforeEach
    void setUp() {
        telegramNotifier = new TelegramNotifier(config, httpSender);
    }

    @Test
    void testSendSyncTelegramDisabledDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(false);

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verifyNoInteractions(httpSender);
    }

    @Test
    void testSendSyncTelegramEnabledMissingTokenDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("");
        // lenient because execution might stop before reading chat ID
        lenient().when(config.getTelegramChatId()).thenReturn("123456");

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verifyNoInteractions(httpSender);
    }

    @Test
    void testSendSyncTelegramEnabledMissingChatIdDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn(null);

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verifyNoInteractions(httpSender);
    }

    @Test
    void testSendSyncSuccess() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn("my-chat-id");

        when(httpSender.postJson(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new TelegramNotifier.HttpResult(200, "OK"));

        SmsMessage message = new SmsMessage(
                1, 
                "TX100", 
                "987654", 
                OffsetDateTime.of(2026, 6, 24, 14, 30, 0, 0, ZoneOffset.ofHours(7))
        );

        telegramNotifier.sendSync(message);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpSender, times(1)).postJson(urlCaptor.capture(), bodyCaptor.capture(), eq(10_000), eq(15_000));

        assertEquals("https://api.telegram.org/botmy-token/sendMessage", urlCaptor.getValue());
        assertTrue(bodyCaptor.getValue().contains("\"chat_id\":\"my-chat-id\""));
        assertTrue(bodyCaptor.getValue().contains("\"parse_mode\":\"HTML\""));
        assertTrue(bodyCaptor.getValue().contains("TX100"));
    }

    @Test
    void testSendSyncApiErrorThrowsException() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn("my-chat-id");

        when(httpSender.postJson(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new TelegramNotifier.HttpResult(400, "Bad Request: chat not found"));

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            telegramNotifier.sendSync(message);
        });

        assertTrue(exception.getMessage().contains("Telegram API trả về HTTP 400"));
        assertTrue(exception.getMessage().contains("Bad Request: chat not found"));
    }
}
