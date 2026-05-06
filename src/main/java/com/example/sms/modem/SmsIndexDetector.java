package com.example.sms.modem;

import com.example.sms.serial.RxBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scans the shared {@link RxBuffer} for {@code +CMTI} unsolicited result codes
 * and extracts the SMS memory indexes that the modem reports.
 *
 * <p>Example notification:
 * <pre>+CMTI: "SM",12</pre>
 *
 * <p>{@link #detect()} is designed to be called each time new data is appended
 * to the buffer.  It is stateless beyond the buffer reference and is therefore
 * thread-safe as long as callers serialise their processing of the returned
 * indexes.
 */
@Component
public class SmsIndexDetector {

    private static final Logger log = LoggerFactory.getLogger(SmsIndexDetector.class);

    /**
     * Matches {@code +CMTI: "SM",12} or {@code +CMTI: "ME",3} etc.
     * Capture group 1 = the integer SMS index.
     */
    private static final Pattern CMTI_PATTERN =
            Pattern.compile("\\+CMTI:\\s*\"[^\"]+\",(\\d+)");

    private final RxBuffer rxBuffer;

    public SmsIndexDetector(RxBuffer rxBuffer) {
        this.rxBuffer = rxBuffer;
    }

    /**
     * Scans buffered data for {@code +CMTI} notifications.
     *
     * @return list of SMS indexes found (may be empty, never null).
     */
    public List<Integer> detect() {
        List<Integer> found = new ArrayList<>();

        String content;
        long   endAbsolute;

        synchronized (rxBuffer) {
            content     = rxBuffer.snapshot();
            endAbsolute = rxBuffer.currentAbsoluteOffset();
        }

        Matcher m       = CMTI_PATTERN.matcher(content);
        int     lastEnd = 0;

        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            found.add(index);
            lastEnd = m.end();
            log.info("New SMS notification detected: index={}", index);
        }

        // Drain everything up to the last matched position so we don't re-process it.
        if (lastEnd > 0) {
            long absoluteEnd = (endAbsolute - content.length()) + lastEnd;
            rxBuffer.drainUpTo(absoluteEnd);
        }

        return found;
    }
}
