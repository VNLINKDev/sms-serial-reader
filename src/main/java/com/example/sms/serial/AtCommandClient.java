package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.SerialPortException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Gateway cấp thấp để gửi AT command tới modem GSM qua serial port.
 *
 * Đọc response trực tiếp từ InputStream của serial port, không dùng buffer
 * trung gian. Trước mỗi command, stale data trong serial buffer được drain
 * để tránh response cũ/unsolicited notification ảnh hưởng kết quả.
 *
 * NOTE: Nếu caller gọi class này đồng thời từ nhiều thread, response có thể
 * bị match sai vì modem không gắn correlation id cho từng command. Luôn gọi qua
 * executor tuần tự ở tầng orchestration.
 */
@RequiredArgsConstructor
public class AtCommandClient {

    private static final Logger log = LoggerFactory.getLogger(AtCommandClient.class);

    private static final int DEFAULT_TIMEOUT_MS = 8_000;
    private static final int STALE_DRAIN_LIMIT_BYTES = 64 * 1024;

    private final SerialPortManager portManager;

    // -------------------------------------------------------------------------
    // API công khai
    // -------------------------------------------------------------------------

    /**
     * Gửi {@code command} và chờ tối đa {@link #DEFAULT_TIMEOUT_MS} ms để nhận
     * phản hồi kết thúc (OK / ERROR).
     *
     * @return nội dung phản hồi từ modem.
     * @throws ModemTimeoutException nếu không nhận được phản hồi kết thúc đúng hạn.
     */
    public String sendAndWait(String command) {
        return sendAndWait(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Xả stale data, gửi {@code command} rồi chờ response kết thúc.
     *
     * Drain trước khi gửi để loại bỏ unsolicited notification (+CMTI, ...)
     * hoặc phần response cũ còn sót trong serial buffer.
     */
    public String sendAndWait(String command, int timeoutMs) {
        drainStaleData();
        sendRaw(command);
        return readResponse(timeoutMs, command);
    }

    /**
     * Gửi lệnh AT thô mà không chờ phản hồi.
     *
     * Dùng nội bộ bởi {@link #sendAndWait(String, int)} và mở ra cho các
     * trường hợp đặc biệt như escape sequence. Method này chỉ đảm bảo flush byte
     * xuống stream, không xác nhận modem đã xử lý command.
     */
    public void sendRaw(String command) {
        OutputStream outputStream = portManager.getOutputStream();
        if (outputStream == null) {
            throw new SerialPortException("Output stream của cổng serial không khả dụng.");
        }
        byte[] bytes = (command + "\r").getBytes(StandardCharsets.US_ASCII);
        try {
            outputStream.write(bytes);
            outputStream.flush();
            log.debug("[TX] {}", command);
        } catch (Exception e) {
            throw new SerialPortException("Không thể ghi lệnh '" + command + "': " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Đọc response trực tiếp từ serial port
    // -------------------------------------------------------------------------

    /**
     * Đọc response trực tiếp từ serial port cho đến khi gặp terminator
     * (OK / ERROR) hoặc hết timeout.
     *
     * Vòng lặp đọc sử dụng serial port ở chế độ semi-blocking (timeout 5s mỗi
     * lần read). Khi {@code is.read()} trả về 0 (hết timeout mà chưa có data),
     * sleep 50ms rồi thử lại — tránh busy-spin nếu driver trả 0 ngay lập tức.
     *
     * @throws ModemTimeoutException khi hết thời gian chờ.
     * @throws SerialPortException   khi serial port stream bị đóng hoặc lỗi I/O.
     */
    private String readResponse(int timeoutMs, String command) {
        InputStream is = portManager.getInputStream();
        StringBuilder response = new StringBuilder();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                int n = is.read(buf);

                if (n < 0) {
                    throw new SerialPortException(
                            "Stream của cổng serial đã đóng khi đang chờ phản hồi cho lệnh: " + command);
                }

                if (n == 0) {
                    // Semi-blocking read timeout — không có data, chờ rồi thử lại.
                    // Sleep ngắn để tránh busy-spin nếu driver trả 0 ngay lập tức.
                    sleepQuietly(50);
                    continue;
                }

                response.append(new String(buf, 0, n, StandardCharsets.US_ASCII));

                String responseStr = response.toString();
                if (isTerminated(responseStr)) {
                    log.debug("[RX] {}", responseStr.trim());
                    return responseStr;
                }

            } catch (ModemTimeoutException | SerialPortException e) {
                throw e;
            } catch (Exception e) {
                if (isReadTimeout(e)) {
                    sleepQuietly(50);
                    continue; // timeout bán-blocking bình thường
                }
                throw new SerialPortException(
                        "Lỗi đọc khi đang chờ phản hồi cho lệnh '" + command + "': " + e.getMessage(), e);
            }
        }

        throw new ModemTimeoutException(command, timeoutMs);
    }

    public void drainStale(){
        drainStaleData();
    }

    /**
     * Xả dữ liệu cũ trong serial port input buffer trước khi gửi command mới.
     *
     * Tránh stale data từ unsolicited notification (+CMTI, ...) hoặc response
     * lệnh cũ ảnh hưởng đến response của lệnh tiếp theo.
     */
    private void drainStaleData() {
        try {
            InputStream is = portManager.getInputStream();
            byte[] buf = new byte[4096];
            int available = is.available();
            int drained = 0;
            while (available > 0 && drained < STALE_DRAIN_LIMIT_BYTES) {
                int read = is.read(buf, 0, Math.min(available, buf.length));
                if (read <= 0)
                    break;
                drained += read;
                log.debug("Đã xả {} byte dữ liệu cũ khỏi cổng serial.", read);
                available = is.available();
            }
            if (available > 0) {
                log.warn("Dừng xả dữ liệu cũ sau {} byte để tránh kẹt vòng lặp đọc serial.", drained);
            }
        } catch (Exception e) {
            log.debug("Không thể xả dữ liệu cũ (không nghiêm trọng): {}", e.getMessage());
        }
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

    private static boolean isReadTimeout(Exception e) {
        String msg = e.getMessage();
        if (msg == null)
            return false;
        String lower = msg.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    /**
     * Ghi thẳng {@code bytes} xuống output stream, KHÔNG tự thêm {@code \r}
     * như {@link #sendRaw(String)}. Dùng khi caller cần tự kiểm soát framing
     * byte-level, ví dụ ghi nội dung SMS kết thúc bằng Ctrl+Z (0x1A).
     */
    public void writeRawBytes(byte[] bytes) {
        OutputStream outputStream = portManager.getOutputStream();
        if (outputStream == null) {
            throw new SerialPortException("Output stream của cổng serial không khả dụng.");
        }
        try {
            outputStream.write(bytes);
            outputStream.flush();
            log.debug("[TX-RAW] {} byte(s)", bytes.length);
        } catch (Exception e) {
            throw new SerialPortException("Không thể ghi dữ liệu thô xuống cổng serial: " + e.getMessage(), e);
        }
    }

    /**
     * Đọc từ serial port cho đến khi buffer chứa {@code token} hoặc hết
     * timeout. Dùng cho dấu nhắc đặc biệt không phải OK/ERROR — ví dụ dấu
     * nhắc {@code >} mà modem trả về sau {@code AT+CMGS} để chờ nội dung SMS.
     *
     * @throws ModemTimeoutException nếu không thấy {@code token} trong hạn.
     * @throws SerialPortException   nếu stream bị đóng hoặc lỗi I/O.
     */
    public String waitForToken(String token, int timeoutMs, String context) {
        InputStream is = portManager.getInputStream();
        StringBuilder buffer = new StringBuilder();
        byte[] buf = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                int n = is.read(buf);
                if (n < 0) {
                    throw new SerialPortException(
                            "Stream của cổng serial đã đóng khi đang chờ token '" + token + "' (" + context + ").");
                }
                if (n == 0) {
                    sleepQuietly(50);
                    continue;
                }
                buffer.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                if (buffer.indexOf(token) >= 0) {
                    return buffer.toString();
                }
            } catch (SerialPortException e) {
                throw e;
            } catch (Exception e) {
                if (isReadTimeout(e)) {
                    sleepQuietly(50);
                    continue;
                }
                throw new SerialPortException(
                        "Lỗi đọc khi chờ token '" + token + "' (" + context + "): " + e.getMessage(), e);
            }
        }
        throw new ModemTimeoutException(context + " (chờ token '" + token + "')", timeoutMs);
    }

    /**
     * Bản public của {@link #readResponse(int, String)} — cho tầng nghiệp vụ
     * đọc response kết thúc bằng OK/ERROR sau khi đã tự ghi dữ liệu thô xuống
     * stream (vd. sau khi ghi nội dung SMS + Ctrl+Z).
     */
    public String readTerminatedResponse(int timeoutMs, String context) {
        return readResponse(timeoutMs, context);
    }

}
