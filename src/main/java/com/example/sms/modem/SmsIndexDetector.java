package com.example.sms.modem;

import com.example.sms.serial.RxBuffer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Quét {@link RxBuffer} dùng chung để tìm mã kết quả tự phát {@code +CMTI}
 * và trích xuất các chỉ số bộ nhớ SMS do modem báo về.
 *
 * Ví dụ thông báo:
 * <pre>+CMTI: "SM",12</pre>
 *
 * {@link #detect()} được gọi từ polling thread và chỉ làm nhiệm vụ tách event
 * notification khỏi raw stream. Sau khi match, phần buffer đã xử lý được drain
 * để tránh đọc lại cùng một index trong vòng polling sau.
 *
 * NOTE: Detector chỉ parse notification, không đọc SMS. Việc đọc nội dung
 * phải được đưa qua executor tuần tự để không cạnh tranh với AT command khác.
 */
@Component
@RequiredArgsConstructor
public class SmsIndexDetector {

    private static final Logger log = LoggerFactory.getLogger(SmsIndexDetector.class);

    /**
     * Khớp {@code +CMTI: "SM",12}, {@code +CMTI: "ME",3}, v.v.
     * Nhóm bắt 1 = chỉ số SMS dạng số nguyên.
     */
    private static final Pattern CMTI_PATTERN =
            Pattern.compile("\\+CMTI:\\s*\"[^\"]+\",(\\d+)");

    private final RxBuffer rxBuffer;

    /**
     * Quét dữ liệu hiện có trong buffer để tìm thông báo {@code +CMTI}.
     *
     * Khối synchronized đảm bảo snapshot và absolute offset nhất quán với nhau.
     * Nếu lấy hai giá trị này ở hai thời điểm khác nhau, phần drain có thể xóa
     * sai đoạn dữ liệu khi serial reader append thêm byte giữa chừng.
     *
     * @return danh sách chỉ số SMS tìm được (có thể rỗng, không bao giờ null).
     */
    public List<Integer> detect() {
        List<Integer> found = new ArrayList<>();

        String content;
        long   endAbsolute;

        synchronized (rxBuffer) {
            content     = rxBuffer.snapshot();
            endAbsolute = rxBuffer.currentAbsoluteOffset();
        }

        Matcher m       = CMTI_PATTERN.matcher(content);
        int     lastEnd = 0;

        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            found.add(index);
            lastEnd = m.end();
            log.info("New SMS notification detected: index={}", index);
        }

        // Xả dữ liệu đến vị trí khớp cuối cùng để không xử lý lại.
        if (lastEnd > 0) {
            long absoluteEnd = (endAbsolute - content.length()) + lastEnd;
            rxBuffer.drainUpTo(absoluteEnd);
        }

        return found;
    }
}
