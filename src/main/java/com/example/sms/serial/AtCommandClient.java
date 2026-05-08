package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.SerialPortException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Thành phần gửi lệnh an toàn luồng để gửi lệnh AT tới modem GSM và chờ phản hồi.
 *
 * <p>Mọi lần gửi lệnh PHẢI được tuần tự hóa qua executor một luồng để tránh
 * ghi xen kẽ vào modem. Bên gọi chịu trách nhiệm tuần tự hóa; lớp này chỉ xử lý
 * việc gửi byte và khớp phản hồi.
 *
 * <p>Việc khớp phản hồi được giao cho {@link RxBuffer} dùng chung, nơi được
 * {@link SerialReaderService} nạp dữ liệu.
 */
@Component
@RequiredArgsConstructor
public class AtCommandClient {

    private static final Logger log = LoggerFactory.getLogger(AtCommandClient.class);

    private static final int DEFAULT_TIMEOUT_MS = 8_000;

    private final SerialPortManager portManager;
    private final RxBuffer     rxBuffer;

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
     * Gửi {@code command} và chờ tối đa {@code timeoutMs} để nhận phản hồi kết thúc.
     */
    public String sendAndWait(String command, int timeoutMs) {
        long startAbsolute = rxBuffer.currentAbsoluteOffset();

        sendRaw(command);

        return rxBuffer.waitForTerminatedResponse(startAbsolute, timeoutMs, command);
    }

    /**
     * Gửi lệnh AT thô mà không chờ phản hồi.
     * Dùng nội bộ và mở ra cho các trường hợp đặc biệt (ví dụ chuỗi escape).
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
}
