package com.example.sms.serial;

import com.example.sms.exception.SerialPortException;
import com.example.sms.config.AppConfig;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Quản lý lifecycle của {@link SerialPort}: chọn port theo cấu hình, mở port,
 * cấu hình thông số truyền, expose input/output stream và đóng tài nguyên.
 *
 * Các lớp cao hơn chỉ làm việc với stream đã được kiểm tra trạng thái,
 * không tự mở/đóng port.
 */
@RequiredArgsConstructor
public class SerialPortManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SerialPortManager.class);

    private static final int READ_TIMEOUT_MS  = 5_000;
    private static final int WRITE_TIMEOUT_MS = 1_000;

    private final AppConfig config;

    private SerialPort   port;
    private InputStream  inputStream;
    private OutputStream outputStream;

    // -------------------------------------------------------------------------
    // Vòng đời
    // -------------------------------------------------------------------------

    /**
     * Mở và cấu hình cổng serial.
     *
     * DTR/RTS được bật để tương thích với nhiều modem USB cần tín hiệu control
     * line trước khi phản hồi AT command. Timeout read ở chế độ semi-blocking để
     * reader thread có thể định kỳ kiểm tra cờ shutdown.
     *
     * @throws SerialPortException nếu không mở được cổng.
     */
    public void open() {
        String portName = config.getSerialPort();
        int baudRate = config.getBaudRate();
        log.info("Đang mở cổng serial {} @ {} baud. Các cổng hiện có: {}",
                portName, baudRate, listAvailablePorts());

        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setDTR();
        port.setRTS();
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                READ_TIMEOUT_MS,
                WRITE_TIMEOUT_MS
        );

        if (!port.openPort()) {
            throw new SerialPortException(
                    "Không thể mở cổng '" + portName + "'. Các cổng hiện có: " + listAvailablePorts());
        }

        inputStream  = port.getInputStream();
        outputStream = port.getOutputStream();

        log.info("Đã mở cổng serial {} thành công.", portName);
    }

    /**
     * Đóng stream trước rồi mới đóng port vật lý để driver có cơ hội flush/giải
     * phóng handle theo thứ tự ổn định.
     */
    @Override
    public void close() {
        if (inputStream != null)  closeQuietly(inputStream);
        if (outputStream != null) closeQuietly(outputStream);

        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("Đã đóng cổng serial {}.", config.getSerialPort());
        }
    }

    /**
     * Đóng port hiện tại và mở lại từ đầu.
     *
     * Đóng port hiện tại và mở lại khi phát hiện lỗi đọc
     * (ví dụ: USB modem bị ngắt rồi cắm lại). Luồng gọi có trách nhiệm
     * chờ một khoảng delay trước khi gọi reconnect để tránh hot-loop.
     *
     * @throws SerialPortException nếu không mở lại được port.
     */
    public void reconnect() {
        log.warn("Đang kết nối lại cổng serial '{}'...", config.getSerialPort());
        close();
        open();
    }

    // -------------------------------------------------------------------------
    // Hàm truy cập
    // -------------------------------------------------------------------------

    public InputStream getInputStream() {
        assertOpen();
        return inputStream;
    }

    public OutputStream getOutputStream() {
        assertOpen();
        return outputStream;
    }

    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    /** Tên cổng serial đang được cấu hình (dùng để log từ các lớp khác). */
    public String getPortName() {
        return config.getSerialPort();
    }

    // -------------------------------------------------------------------------
    // Tiện ích
    // -------------------------------------------------------------------------

    /** Trả về danh sách tên các cổng serial phát hiện được, phân tách bằng dấu phẩy. */
    public static String listAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) return "(none found)";
        return Arrays.stream(ports)
                .map(SerialPort::getSystemPortName)
                .collect(Collectors.joining(", "));
    }

    private void assertOpen() {
        if (!isOpen()) {
            throw new SerialPortException("Cổng serial chưa được mở.");
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try { c.close(); } catch (Exception ignored) {}
    }
}
