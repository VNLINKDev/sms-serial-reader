package com.example.sms.serial;

import com.example.sms.exception.ModemTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread-safe receive buffer that accumulates raw bytes from the serial port.
 *
 * <p>The buffer is automatically trimmed when it exceeds {@link #MAX_SIZE} bytes,
 * keeping the most recent {@link #KEEP_TAIL} bytes.  An absolute offset counter
 * tracks how many bytes have been consumed since the buffer was created, so
 * waiting threads can correctly reference positions even after trimming.
 */
@Component
public class RxBuffer {

    private static final Logger log = LoggerFactory.getLogger(RxBuffer.class);

    private static final int MAX_SIZE  = 8_000;
    private static final int KEEP_TAIL = 2_000;

    private final StringBuilder buffer     = new StringBuilder();
    private long                baseOffset = 0;   // bytes trimmed away so far

    // -------------------------------------------------------------------------
    // Writer side (serial reader thread)
    // -------------------------------------------------------------------------

    /** Appends newly received data and notifies waiting threads. */
    public synchronized void append(String data) {
        buffer.append(data);

        if (buffer.length() > MAX_SIZE) {
            int cut = buffer.length() - KEEP_TAIL;
            buffer.delete(0, cut);
            baseOffset += cut;
            log.debug("RxBuffer trimmed {} bytes; baseOffset={}", cut, baseOffset);
        }

        notifyAll();
    }

    // -------------------------------------------------------------------------
    // Reader side (command thread)
    // -------------------------------------------------------------------------

    /**
     * Returns the absolute offset that the next byte appended will occupy.
     * Callers should capture this <em>before</em> sending an AT command so they
     * can later ask for only the response that appeared after the command.
     */
    public synchronized long currentAbsoluteOffset() {
        return baseOffset + buffer.length();
    }

    /**
     * Blocks until a terminal response (OK / ERROR) appears at or after
     * {@code startAbsoluteOffset}, or until {@code timeoutMs} elapses.
     *
     * @throws ModemTimeoutException on timeout.
     */
    public String waitForTerminatedResponse(long startAbsoluteOffset,
                                            int timeoutMs,
                                            String command) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        synchronized (this) {
            while (true) {
                long localStart = startAbsoluteOffset - baseOffset;
                if (localStart < 0) localStart = 0;

                if (localStart <= buffer.length()) {
                    String candidate = buffer.substring((int) localStart);
                    if (isTerminated(candidate)) {
                        log.debug("[RX] {}", candidate.trim());
                        return candidate;
                    }
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new ModemTimeoutException(command, timeoutMs);
                }

                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModemTimeoutException(command, timeoutMs);
                }
            }
        }
    }

    /**
     * Returns a snapshot of the raw buffer contents and removes everything up to
     * the given absolute offset.  Used by {@link com.example.sms.modem.SmsIndexDetector}
     * to drain processed content.
     */
    public synchronized String drainUpTo(long absoluteOffset) {
        long localEnd = absoluteOffset - baseOffset;
        if (localEnd <= 0) return "";
        if (localEnd > buffer.length()) localEnd = buffer.length();

        String result = buffer.substring(0, (int) localEnd);
        buffer.delete(0, (int) localEnd);
        baseOffset += localEnd;
        return result;
    }

    /** Returns a snapshot of the full buffer without modifying it. */
    public synchronized String snapshot() {
        return buffer.toString();
    }

    /** Wakes all waiting threads (used during shutdown). */
    public synchronized void wakeAll() {
        notifyAll();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isTerminated(String s) {
        return s.contains("\r\nOK\r\n")
            || s.contains("\nOK\r\n")
            || s.contains("\r\nERROR\r\n")
            || s.contains("+CME ERROR")
            || s.contains("+CMS ERROR");
    }
}
