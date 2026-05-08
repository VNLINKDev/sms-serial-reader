package com.example.sms.serial;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Service đọc byte thô từ serial port trên một daemon thread riêng và nạp vào
 * {@link RxBuffer}.
 *
 * Lớp này là boundary I/O thấp nhất của ứng dụng: không parse modem command,
 * không hiểu SMS, không publish Redis. Tách trách nhiệm như vậy giúp giữ read
 * loop đơn giản và giảm nguy cơ block serial reader bởi business logic.
 *
 * {@code running} là volatile để shutdown thread khác có thể báo dừng mà
 * reader thread nhìn thấy ngay cả khi không đi qua synchronized block.
 */
@Component
@RequiredArgsConstructor
public class SerialReaderService {

    private static final Logger log = LoggerFactory.getLogger(SerialReaderService.class);

    private final SerialPortManager portManager;
    private final RxBuffer    rxBuffer;

    private volatile boolean running = false;
    private Thread            readerThread;

    // -------------------------------------------------------------------------
    // Vòng đời
    // -------------------------------------------------------------------------

    /**
     * Khởi động reader thread nền.
     *
     * Thread là daemon để JVM không bị giữ lại nếu Spring context shutdown
     * trong tình huống lỗi startup. Lifecycle bình thường vẫn gọi {@link #stop()}.
     */
    public void start() {
        running      = true;
        readerThread = new Thread(this::readLoop, "sms-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("Serial reader thread started.");
    }

    /**
     * Báo dừng reader thread và đánh thức các command đang wait trên buffer.
     *
     * Interrupt giúp thoát khỏi blocking read nếu driver hỗ trợ; wakeAll giúp
     * các thread chờ response không nằm im đến hết timeout trong quá trình shutdown.
     */
    public void stop() {
        running = false;
        rxBuffer.wakeAll();

        if (readerThread != null) {
            readerThread.interrupt();
        }

        log.info("Serial reader thread stopped.");
    }

    // -------------------------------------------------------------------------
    // Vòng lặp đọc
    // -------------------------------------------------------------------------

    private void readLoop() {
        InputStream inputStream = portManager.getInputStream();
        byte[] buf = new byte[1024];

        while (running) {
            try {
                int n = inputStream.read(buf);
                if (n <= 0) continue;

                String data = new String(buf, 0, n, StandardCharsets.US_ASCII);
                rxBuffer.append(data);

            } catch (Exception e) {
                if (!running) break;
                if (isReadTimeout(e)) continue;

                // NOTE: Lỗi đọc serial thường là lỗi thiết bị/driver; dừng loop để tránh log spam vô hạn.
                log.error("Error reading from serial port: {}", e.getMessage(), e);
                break;
            }
        }

        log.debug("Read loop exiting.");
    }

    private static boolean isReadTimeout(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout");
    }
}
