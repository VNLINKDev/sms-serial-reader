package com.example.sms.serial;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Continuously reads raw bytes from a serial {@link InputStream} on a dedicated
 * daemon thread and appends them to the shared {@link RxBuffer}.
 *
 * <p>This class is intentionally free of modem or SMS logic; it only moves bytes
 * from the wire to memory.
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
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Starts the background reader thread. */
    public void start() {
        running      = true;
        readerThread = new Thread(this::readLoop, "sms-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("Serial reader thread started.");
    }

    /** Signals the reader to stop and wakes any waiting threads. */
    public void stop() {
        running = false;
        rxBuffer.wakeAll();

        if (readerThread != null) {
            readerThread.interrupt();
        }

        log.info("Serial reader thread stopped.");
    }

    // -------------------------------------------------------------------------
    // Read loop
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
