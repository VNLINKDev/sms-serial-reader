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

/**
 * Gửi background notification lên Telegram group khi nhận OTP SMS.
 *
 * <p>Sử dụng Telegram Bot API endpoint {@code sendMessage} qua HTTPS POST.
 * Gửi bất đồng bộ (fire-and-forget) trên virtual thread để không block
 * luồng xử lý SMS chính. Lỗi gửi Telegram chỉ log warn, không throw.</p>
 *
 * <p>Tính năng được bật/tắt qua env var {@code TELEGRAM_ENABLED}.</p>
 */
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /**
     * Gửi notification Telegram bất đồng bộ (fire-and-forget).
     *
     * <p>Trả về ngay lập tức, gửi HTTP request trên virtual thread riêng.
     * Không ảnh hưởng đến luồng xử lý SMS hay Redis publish.</p>
     *
     * @param msg SMS OTP đã được parse thành công.
     */
    public void notifyAsync(SmsMessage msg) {
        if (!config.isTelegramEnabled()) {
            log.warn("Telegram is DISABLED (TELEGRAM_ENABLED=false), skipping notify for SMS index={}.", msg.getIndex());
            return;
        }

        String botToken = config.getTelegramBotToken();
        String chatId   = config.getTelegramChatId();

        if (botToken == null || botToken.isBlank()) {
            log.warn("Telegram notification skipped: TELEGRAM_BOT_TOKEN is not configured.");
            return;
        }
        if (chatId == null || chatId.isBlank()) {
            log.warn("Telegram notification skipped: TELEGRAM_CHAT_ID is not configured.");
            return;
        }

        log.info("Telegram notification queued for SMS index={} transactionId={}.",
                msg.getIndex(), msg.getTransactionId());

        // Capture reference to avoid capturing `this` heavily in lambda
        String url  = TELEGRAM_API_BASE + botToken + "/sendMessage";
        String text = buildMessageText(msg);

        // Dùng platform thread thông thường, tương thích Java 11+
        Thread t = new Thread(() -> {
            try {
                sendMessage(url, chatId, text, msg.getIndex());
            } catch (Exception e) {
                log.warn("Telegram notification failed for SMS index={}: {}",
                        msg.getIndex(), e.getMessage(), e);
            }
        }, "telegram-notify-" + msg.getIndex());
        t.setDaemon(true);
        t.start();
    }

    /**
     * Thực hiện HTTP POST đến Telegram API.
     *
     * @param url    Telegram sendMessage endpoint (đã gắn bot token).
     * @param chatId Chat ID đích.
     * @param text   Nội dung message (MarkdownV2).
     */
    private void sendMessage(String url, String chatId, String text, int smsIndex) throws Exception {
        Map<String, String> payload = Map.of(
                "chat_id",    chatId,
                "text",       text,
                "parse_mode", "HTML"
        );

        String body = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        log.debug("Sending Telegram HTTP request for SMS index={}...", smsIndex);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Telegram notification sent OK for SMS index={}.", smsIndex);
        } else {
            log.warn("Telegram API returned HTTP {} for SMS index={}. Body: {}",
                    response.statusCode(), smsIndex, response.body());
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
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }
}
