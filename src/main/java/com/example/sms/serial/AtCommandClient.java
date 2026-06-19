package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.SerialPortException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Gateway cấp thấp để gửi AT command tới modem GSM qua serial port.
 *
 * Lớp này không tự tạo lock ở cấp command vì runtime đã serialize tất cả
 * modem command qua single-thread executor. Ranh giới trách nhiệm ở đây là:
 * lấy offset hiện tại của {@link RxBuffer}, ghi command xuống output stream,
 * rồi chờ phần response xuất hiện sau offset đó.
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
     * Gửi {@code command} và chờ response kết thúc sau offset hiện tại của buffer.
     *
     * Offset được lấy trước khi ghi command để bỏ qua dữ liệu cũ còn trong
     * buffer, đồng thời vẫn nhận được toàn bộ response phát sinh sau command.
     */
    public String sendAndWait(String command, int timeoutMs) {
        long startAbsolute = rxBuffer.currentAbsoluteOffset();

        sendRaw(command);

        return rxBuffer.waitForTerminatedResponse(startAbsolute, timeoutMs, command);
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
}
