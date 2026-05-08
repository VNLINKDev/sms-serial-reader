package com.example.sms.serial;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Liên tục đọc byte thô từ {@link InputStream} serial trên một daemon thread
 * riêng và thêm vào {@link RxBuffer} dùng chung.
 *
 * <p>Lớp này cố ý không chứa logic modem hoặc SMS; nó chỉ chuyển byte từ đường
 * truyền vào bộ nhớ.
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

    /** Khởi động luồng đọc nền. */
    public void start() {
        running      = true;
        readerThread = new Thread(this::readLoop, "sms-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("Serial reader thread started.");
    }

    /** Báo hiệu dừng luồng đọc và đánh thức các luồng đang chờ. */
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
