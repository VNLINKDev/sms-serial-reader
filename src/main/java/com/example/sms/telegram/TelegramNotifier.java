package com.example.sms.telegram;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final HttpSender httpSender;

    public TelegramNotifier(AppConfig config) {
        this(config, new UrlConnectionHttpSender());
    }

    TelegramNotifier(AppConfig config, HttpSender httpSender) {
        this.config = config;
        this.httpSender = httpSender;
        this.objectMapper = new ObjectMapper();
    }

    public void sendSync(SmsMessage msg) throws Exception {
        if (!config.isTelegramEnabled()) {
            log.warn("Telegram đang TẮT (TELEGRAM_ENABLED=false), bỏ qua thông báo cho SMS index={}.",
                    msg.getIndex());
            return;
        }

        String botToken = config.getTelegramBotToken();
        String chatId = config.getTelegramChatId();

        if (botToken == null || botToken.isBlank()) {
            log.warn("Bỏ qua thông báo Telegram: TELEGRAM_BOT_TOKEN chưa được cấu hình.");
            return;
        }
        if (chatId == null || chatId.isBlank()) {
            log.warn("Bỏ qua thông báo Telegram: TELEGRAM_CHAT_ID chưa được cấu hình.");
            return;
        }

        String url = TELEGRAM_API_BASE + botToken + "/sendMessage";
        String text = buildMessageText(msg);

        log.debug("Đang gửi thông báo Telegram cho SMS index={} transactionId={}...",
                msg.getIndex(), msg.getTransactionId());

        sendMessage(url, chatId, text, msg.getIndex());
    }

    /**
     * Thực hiện HTTP POST đến Telegram Bot API.
     *
     * @param url      Telegram sendMessage endpoint (đã gắn bot token).
     * @param chatId   Chat ID đích.
     * @param text     Nội dung message (HTML).
     * @param smsIndex Index của SMS, dùng cho log.
     * @throws Exception nếu HTTP request thất bại hoặc response không phải 200.
     */
    private void sendMessage(String url, String chatId, String text, int smsIndex) throws Exception {
        Map<String, String> payload = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML");

        String body = objectMapper.writeValueAsString(payload);

        HttpResult response = httpSender.postJson(url, body, 10_000, 15_000);

        if (response.statusCode == 200) {
            log.debug("Telegram trả về HTTP 200 OK cho SMS index={}.", smsIndex);
        } else {
            // Ném exception để caller biết gửi thất bại → lastTelegramTransactionId không
            // cập nhật
            throw new RuntimeException(
                    "Telegram API trả về HTTP " + response.statusCode
                            + " cho SMS index=" + smsIndex
                            + ". Nội dung: " + response.body);
        }
    }

    /**
     * Tạo nội dung message hiển thị OTP trên Telegram (định dạng HTML).
     */
    private String buildMessageText(SmsMessage msg) {
        String timestamp = msg.getTimestamp() != null
                ? msg.getTimestamp().format(DISPLAY_FMT)
                : "N/A";

        return "🔔 <b>OTP Nhận Được</b>\n\n"
                + "☎\uFE0F <b>Số điện thoại:</b> <code>" + escapeHtml(config.getPhoneNumber()) + "</code>\n"
                + "🔑 <b>Mã giao dịch:</b> <code>" + escapeHtml(msg.getTransactionId()) + "</code>\n"
                + "🔐 <b>OTP:</b> <code>" + escapeHtml(msg.getOtp()) + "</code>\n"
                + "🕐 <b>Thời gian:</b> " + escapeHtml(timestamp);
    }

    /**
     * Escape các ký tự đặc biệt HTML để tránh lỗi parse message Telegram.
     */
    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @FunctionalInterface
    interface HttpSender {
        HttpResult postJson(String url, String body, int connectTimeoutMs, int readTimeoutMs) throws Exception;
    }

    static final class HttpResult {
        private final int statusCode;
        private final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static final class UrlConnectionHttpSender implements HttpSender {
        @Override
        public HttpResult postJson(String url, String body, int connectTimeoutMs, int readTimeoutMs) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload);
                }

                int statusCode = connection.getResponseCode();
                String responseBody = readBody(
                        statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream());
                return new HttpResult(statusCode, responseBody);
            } finally {
                connection.disconnect();
            }
        }

        private static String readBody(InputStream inputStream) throws Exception {
            if (inputStream == null) {
                return "";
            }
            try (InputStream is = inputStream) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}
