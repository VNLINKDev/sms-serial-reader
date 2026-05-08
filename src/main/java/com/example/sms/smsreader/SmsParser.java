package com.example.sms.smsreader;

import com.example.sms.exception.SmsParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parser chuyển raw response của lệnh {@code AT+CMGR} thành domain model
 * {@link SmsMessage}.
 *
 * Parser chỉ chấp nhận format SMS nghiệp vụ hiện tại: body phải chứa mã giao
 * dịch và OTP. Nếu thiếu header hoặc không trích xuất được OTP, method ném
 * {@link SmsParseException} để caller quyết định retry/log theo index SMS.
 *
 * Edge case: timestamp modem có thể thiếu hoặc sai format. Khi đó parser dùng
 * thời điểm hiện tại để không làm rơi message, nhưng log/monitoring phía trên nên
 * theo dõi tỷ lệ parse fallback nếu cần độ chính xác thời gian cao.
 */
@Component
public class SmsParser {

    private static final Logger log = LoggerFactory.getLogger(SmsParser.class);

    private static final Pattern CMGR_HEADER_PATTERN = Pattern.compile(
            "\\+CMGR:\\s*\"([^\"]*)\",\"([^\"]*)\",(?:[^,]*),\"([^\"]*)\"");

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{2})/(\\d{2})/(\\d{2}),(\\d{2}):(\\d{2}):(\\d{2})([+-])(\\d+)");

    private static final Pattern OTP_PATTERN = Pattern.compile(
            "Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Parse response đầy đủ của {@code AT+CMGR=index}.
     *
     * Flow xử lý: tìm header {@code +CMGR}, lấy timestamp từ header, gom các
     * dòng body cho đến terminal response {@code OK}/{@code ERROR}, sau đó trích
     * transactionId và OTP bằng regex nghiệp vụ.
     */
    public SmsMessage parse(int index, String rawResponse) {

        String[] lines = rawResponse.split("\\r?\\n");

        String headerLine = null;
        int headerIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("+CMGR:")) {
                headerLine = lines[i].trim();
                headerIndex = i;
                break;
            }
        }

        if (headerLine == null) {
            throw new SmsParseException("No +CMGR header found");
        }

        Matcher headerMatcher = CMGR_HEADER_PATTERN.matcher(headerLine);
        if (!headerMatcher.find()) {
            throw new SmsParseException("Invalid CMGR header");
        }

        String tsRaw = headerMatcher.group(3);

        // Body có thể nhiều dòng; terminal line không thuộc nội dung SMS.
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = headerIndex + 1; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().equals("OK") || line.trim().equals("ERROR")) break;

            if (bodyBuilder.length() > 0) bodyBuilder.append('\n');
            bodyBuilder.append(line);
        }

        String body = bodyBuilder.toString().trim();

        Matcher otpMatcher = OTP_PATTERN.matcher(body);

        if (!otpMatcher.find()) {
            throw new SmsParseException("Cannot extract OTP from SMS body: " + body);
        }

        String transactionId = otpMatcher.group(1);
        String otp = otpMatcher.group(2);

        OffsetDateTime timestamp = parseTimestamp(tsRaw);

        return new SmsMessage(index, transactionId, otp, timestamp);
    }

    /**
     * Parse timestamp chuẩn GSM dạng {@code yy/MM/dd,HH:mm:ss+tz}.
     *
     * Timezone trong SMS là số quarter-hour, nên cần nhân 15 phút để ra offset
     * giây. Fallback về {@link OffsetDateTime#now()} là lựa chọn có chủ đích để
     * giữ message trong pipeline khi modem trả timestamp không chuẩn.
     */
    private OffsetDateTime parseTimestamp(String tsRaw) {

        if (tsRaw == null || tsRaw.isBlank()) {
            return OffsetDateTime.now();
        }

        Matcher m = TIMESTAMP_PATTERN.matcher(tsRaw);
        if (!m.find()) {
            return OffsetDateTime.now();
        }

        try {
            int year = 2000 + Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = Integer.parseInt(m.group(6));
            int sign = "+".equals(m.group(7)) ? 1 : -1;
            int quarterHours = Integer.parseInt(m.group(8));

            int offsetSeconds = sign * quarterHours * 15 * 60;

            return OffsetDateTime.of(
                    year, month, day,
                    hour, minute, second,
                    0,
                    ZoneOffset.ofTotalSeconds(offsetSeconds)
            );

        } catch (DateTimeParseException | NumberFormatException e) {
            return OffsetDateTime.now();
        }
    }
}
