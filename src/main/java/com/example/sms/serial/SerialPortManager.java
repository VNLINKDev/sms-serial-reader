package com.example.sms.serial;

import com.example.sms.exception.SerialPortException;
import com.example.sms.config.AppConfig;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Chỉ chịu trách nhiệm vòng đời của {@link SerialPort}: mở, cấu hình,
 * cung cấp luồng dữ liệu và đóng. Các xử lý cấp cao hơn nằm ở lớp khác.
 */
@Component
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
     * @throws SerialPortException nếu không mở được cổng.
     */
    public void open() {
        String portName = config.getSerialPort();
        int baudRate = config.getBaudRate();
        log.info("Opening serial port {} @ {} baud. Available ports: {}",
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
                    "Cannot open port '" + portName + "'. Available: " + listAvailablePorts());
        }

        inputStream  = port.getInputStream();
        outputStream = port.getOutputStream();

        log.info("Serial port {} opened successfully.", portName);
    }

    @PostConstruct
    void init() {
        open();
    }

    @Override
    public void close() {
        if (inputStream != null)  closeQuietly(inputStream);
        if (outputStream != null) closeQuietly(outputStream);

        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("Serial port {} closed.", config.getSerialPort());
        }
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
            throw new SerialPortException("Serial port is not open.");
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try { c.close(); } catch (Exception ignored) {}
    }
}
