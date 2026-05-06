package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.SerialPortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Thread-safe client for sending AT commands to a GSM modem and waiting for
 * their response.
 *
 * <p>All command submissions MUST be serialised through a single-thread executor
 * to avoid interleaved writes to the modem.  The caller is responsible for that
 * serialisation; this class only deals with sending bytes and matching responses.
 *
 * <p>Response matching is delegated to a shared {@link RxBuffer} that is
 * populated by {@link SerialReaderService}.
 */
@Component
public class AtCommandClient {

    private static final Logger log = LoggerFactory.getLogger(AtCommandClient.class);

    private static final int DEFAULT_TIMEOUT_MS = 8_000;

    private final OutputStream outputStream;
    private final RxBuffer     rxBuffer;

    public AtCommandClient(SerialPortManager portManager, RxBuffer rxBuffer) {
        this.outputStream = portManager.getOutputStream();
        this.rxBuffer     = rxBuffer;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends {@code command} and waits up to {@link #DEFAULT_TIMEOUT_MS} ms for
     * a terminal response (OK / ERROR).
     *
     * @return the modem's response text.
     * @throws ModemTimeoutException if no terminal response arrives in time.
     */
    public String sendAndWait(String command) {
        return sendAndWait(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Sends {@code command} and waits up to {@code timeoutMs} for a terminal
     * response.
     */
    public String sendAndWait(String command, int timeoutMs) {
        long startAbsolute = rxBuffer.currentAbsoluteOffset();

        sendRaw(command);

        return rxBuffer.waitForTerminatedResponse(startAbsolute, timeoutMs, command);
    }

    /**
     * Sends a raw AT command without waiting for a response.
     * Used internally and exposed for special cases (e.g. escape sequences).
     */
    public void sendRaw(String command) {
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
