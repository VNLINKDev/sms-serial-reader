package com.example.sms.telegram;

import com.example.sms.config.AppConfig;
import com.example.sms.smsreader.SmsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramNotifier(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.debug("Telegram trả về HTTP 200 OK cho SMS index={}.", smsIndex);
        } else {
            // Ném exception để caller biết gửi thất bại → lastTelegramTransactionId không
            // cập nhật
            throw new RuntimeException(
                    "Telegram API trả về HTTP " + response.statusCode()
                            + " cho SMS index=" + smsIndex
                            + ". Nội dung: " + response.body());
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
}
