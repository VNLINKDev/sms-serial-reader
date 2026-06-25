package com.example.sms.telegram;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotifierTest {

    @Mock
    private AppConfig config;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @InjectMocks
    private TelegramNotifier telegramNotifier;

    @BeforeEach
    void setUp() throws Exception {
        // Inject mock HttpClient using reflection
        Field field = telegramNotifier.getClass().getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(telegramNotifier, httpClient);
    }

    @Test
    void testSendSyncTelegramDisabledDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(false);

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSendSyncTelegramEnabledMissingTokenDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("");
        // lenient because execution might stop before reading chat ID
        lenient().when(config.getTelegramChatId()).thenReturn("123456");

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSendSyncTelegramEnabledMissingChatIdDoesNotSend() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn(null);

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());
        telegramNotifier.sendSync(message);

        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testSendSyncSuccess() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn("my-chat-id");

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        SmsMessage message = new SmsMessage(
                1, 
                "TX100", 
                "987654", 
                OffsetDateTime.of(2026, 6, 24, 14, 30, 0, 0, ZoneOffset.ofHours(7))
        );

        telegramNotifier.sendSync(message);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals("https://api.telegram.org/botmy-token/sendMessage", request.uri().toString());
        assertEquals("application/json; charset=UTF-8", request.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void testSendSyncApiErrorThrowsException() throws Exception {
        when(config.isTelegramEnabled()).thenReturn(true);
        when(config.getTelegramBotToken()).thenReturn("my-token");
        when(config.getTelegramChatId()).thenReturn("my-chat-id");

        when(httpResponse.statusCode()).thenReturn(400);
        when(httpResponse.body()).thenReturn("Bad Request: chat not found");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        SmsMessage message = new SmsMessage(1, "TX100", "987654", OffsetDateTime.now());

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            telegramNotifier.sendSync(message);
        });

        assertTrue(exception.getMessage().contains("Telegram API trả về HTTP 400"));
        assertTrue(exception.getMessage().contains("Bad Request: chat not found"));
    }
}
