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
            throw new SerialPortException("Serial output stream is not available.");
        }
        byte[] bytes = (command + "\r").getBytes(StandardCharsets.US_ASCII);
        try {
            outputStream.write(bytes);
            outputStream.flush();
            log.debug("[TX] {}", command);
        } catch (Exception e) {
            throw new SerialPortException("Failed to write command '" + command + "': " + e.getMessage(), e);
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
                            "Serial port stream closed while waiting for response to: " + command);
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
                    continue; // timeout bán-blocking bình thường
                }
                throw new SerialPortException(
                        "Read error while waiting for response to '" + command + "': " + e.getMessage(), e);
            }
        }

        throw new ModemTimeoutException(command, timeoutMs);
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
            while (available > 0) {
                int read = is.read(buf, 0, Math.min(available, buf.length));
                if (read <= 0)
                    break;
                log.debug("Drained {} stale bytes from serial port.", read);
                available = is.available();
            }
        } catch (Exception e) {
            log.debug("Failed to drain stale data (non-critical): {}", e.getMessage());
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
}
