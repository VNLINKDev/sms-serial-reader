package com.example.sms.smsreader;

import com.example.sms.serial.AtCommandClient;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.sms.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Điều phối việc đọc SMS theo chỉ số bộ nhớ:
 * <ol>
 *   <li>Gửi {@code AT+CMGR=index} qua AT client.</li>
 *   <li>Phân tích phản hồi bằng {@link SmsParser}.</li>
 *   <li>Tùy chọn xóa SMS khỏi bộ nhớ modem.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final Pattern CMGL_INDEX_PATTERN = Pattern.compile("\\+CMGL:\\s*(\\d+),");

    private final AtCommandClient atClient;
    private final SmsParser       smsParser;
    private final AppConfig       config;

    /**
     * Đọc và phân tích SMS tại chỉ số bộ nhớ modem được truyền vào.
     *
     * @param index chỉ số bộ nhớ modem từ thông báo +CMTI.
     * @return {@link Optional} chứa tin nhắn đã phân tích, hoặc rỗng nếu đọc thất bại.
     */
    public Optional<SmsMessage> readAndParse(int index) {
        log.info("Reading SMS at index {}...", index);
        try {
            String response = atClient.sendAndWait("AT+CMGR=" + index);
            log.info("Raw response for index {}: {}", index, response);
            SmsMessage msg  = smsParser.parse(index, response);
            log.info("Parsed SMS at index {}: {}", index, msg);
            if (config.isDeleteSmsAfterRead()) {
                deleteSms(index);
            }

            return Optional.of(msg);

        } catch (Exception e) {
            log.error("Failed to read/parse SMS at index {}: {}", index, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Liệt kê tất cả SMS hiện đang lưu trên modem (tiện ích gỡ lỗi).
     */
    public String listAll() {
        return atClient.sendAndWait("AT+CMGL=\"ALL\"");
    }

    /**
     * Liệt kê các chỉ số bộ nhớ SMS chưa đọc từ modem.
     */
    public List<Integer> listUnreadIndexes() {
        String response = atClient.sendAndWait("AT+CMGL=\"REC UNREAD\"");
        log.info("Raw unread SMS list response: {}", response);

        List<Integer> indexes = new ArrayList<>();
        Matcher matcher = CMGL_INDEX_PATTERN.matcher(response);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }

        return indexes;
    }
    
    /**
     * Xóa SMS tại chỉ số được chỉ định khỏi bộ nhớ modem.
     */
    public void deleteSms(int index) {
        try {
            atClient.sendAndWait("AT+CMGD=" + index);
            log.info("Deleted SMS at index {}.", index);
        } catch (Exception e) {
            log.warn("Could not delete SMS at index {}: {}", index, e.getMessage());
        }
    }
}
