package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bộ đệm nhận an toàn luồng để tích lũy byte thô từ cổng serial.
 *
 * <p>Bộ đệm tự động cắt bớt khi vượt quá {@link #MAX_SIZE} byte, giữ lại
 * {@link #KEEP_TAIL} byte mới nhất. Bộ đếm offset tuyệt đối theo dõi số byte
 * đã bị tiêu thụ từ khi tạo bộ đệm, để các luồng đang chờ vẫn tham chiếu đúng
 * vị trí ngay cả sau khi cắt bớt.
 */
@Component
public class RxBuffer {

    private static final Logger log = LoggerFactory.getLogger(RxBuffer.class);

    private static final int MAX_SIZE  = 8_000;
    private static final int KEEP_TAIL = 2_000;

    private final StringBuilder buffer     = new StringBuilder();
    private long                baseOffset = 0;   // số byte đã bị cắt bỏ

    // -------------------------------------------------------------------------
    // Phía ghi (luồng đọc serial)
    // -------------------------------------------------------------------------

    /** Thêm dữ liệu mới nhận và thông báo cho các luồng đang chờ. */
    public synchronized void append(String data) {
        buffer.append(data);

        if (buffer.length() > MAX_SIZE) {
            int cut = buffer.length() - KEEP_TAIL;
            buffer.delete(0, cut);
            baseOffset += cut;
            log.debug("RxBuffer trimmed {} bytes; baseOffset={}", cut, baseOffset);
        }

        notifyAll();
    }

    // -------------------------------------------------------------------------
    // Phía đọc (luồng lệnh)
    // -------------------------------------------------------------------------

    /**
     * Trả về offset tuyệt đối mà byte tiếp theo được thêm vào sẽ chiếm.
     * Bên gọi nên lấy giá trị này <em>trước</em> khi gửi lệnh AT để sau đó chỉ
     * lấy phần phản hồi xuất hiện sau lệnh.
     */
    public synchronized long currentAbsoluteOffset() {
        return baseOffset + buffer.length();
    }

    /**
     * Chặn cho đến khi phản hồi kết thúc (OK / ERROR) xuất hiện tại hoặc sau
     * {@code startAbsoluteOffset}, hoặc đến khi hết {@code timeoutMs}.
     *
     * @throws ModemTimeoutException khi hết thời gian chờ.
     */
    public String waitForTerminatedResponse(long startAbsoluteOffset,
                                            int timeoutMs,
                                            String command) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        synchronized (this) {
            while (true) {
                long localStart = startAbsoluteOffset - baseOffset;
                if (localStart < 0) localStart = 0;

                if (localStart <= buffer.length()) {
                    String candidate = buffer.substring((int) localStart);
                    if (isTerminated(candidate)) {
                        log.debug("[RX] {}", candidate.trim());
                        return candidate;
                    }
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new ModemTimeoutException(command, timeoutMs);
                }

                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModemTimeoutException(command, timeoutMs);
                }
            }
        }
    }

    /**
     * Trả về ảnh chụp nội dung bộ đệm thô và xóa mọi dữ liệu đến offset tuyệt đối
     * được truyền vào. Được {@link com.example.sms.modem.SmsIndexDetector} dùng
     * để xả nội dung đã xử lý.
     */
    public synchronized String drainUpTo(long absoluteOffset) {
        long localEnd = absoluteOffset - baseOffset;
        if (localEnd <= 0) return "";
        if (localEnd > buffer.length()) localEnd = buffer.length();

        String result = buffer.substring(0, (int) localEnd);
        buffer.delete(0, (int) localEnd);
        baseOffset += localEnd;
        return result;
    }

    /** Trả về ảnh chụp toàn bộ bộ đệm mà không thay đổi nội dung. */
    public synchronized String snapshot() {
        return buffer.toString();
    }

    /** Đánh thức tất cả luồng đang chờ (dùng khi shutdown). */
    public synchronized void wakeAll() {
        notifyAll();
    }

    // -------------------------------------------------------------------------
    // Hàm hỗ trợ
    // -------------------------------------------------------------------------

    private static boolean isTerminated(String s) {
        return s.contains("\r\nOK\r\n")
            || s.contains("\nOK\r\n")
            || s.contains("\r\nERROR\r\n")
            || s.contains("+CME ERROR")
            || s.contains("+CMS ERROR");
    }
}
