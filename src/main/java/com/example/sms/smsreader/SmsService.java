package com.example.sms.smsreader;

import com.example.sms.serial.AtCommandClient;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.sms.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates reading an SMS by its memory index:
 * <ol>
 *   <li>Send {@code AT+CMGR=index} via the AT client.</li>
 *   <li>Parse the response with {@link SmsParser}.</li>
 *   <li>Optionally delete the SMS from modem memory.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final Pattern CMGL_INDEX_PATTERN = Pattern.compile("\\+CMGL:\\s*(\\d+),");

    private final AtCommandClient atClient;
    private final SmsParser       smsParser;
    private final AppConfig       config;

    /**
     * Reads and parses the SMS at the given modem memory index.
     *
     * @param index  modem memory index from the +CMTI notification.
     * @return an {@link Optional} containing the parsed message, or empty if reading fails.
     */
    public Optional<SmsMessage> readAndParse(int index) {
        log.info("Reading SMS at index {}...", index);
        try {
            String response = atClient.sendAndWait("AT+CMGR=" + index);
            log.info("Raw response for index {}: {}", index, response);
            SmsMessage msg  = smsParser.parse(index, response);
            log.info("Parsed SMS at index {}: {}", index, msg);
            if (config.isDeleteSmsAfterRead()) {
                deleteSms(index);
            }

            return Optional.of(msg);

        } catch (Exception e) {
            log.error("Failed to read/parse SMS at index {}: {}", index, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Lists all SMS messages currently stored on the modem (debug utility).
     */
    public String listAll() {
        return atClient.sendAndWait("AT+CMGL=\"ALL\"");
    }

    /**
     * Lists unread SMS memory indexes from the modem.
     */
    public List<Integer> listUnreadIndexes() {
        String response = atClient.sendAndWait("AT+CMGL=\"REC UNREAD\"");
        log.info("Raw unread SMS list response: {}", response);

        List<Integer> indexes = new ArrayList<>();
        Matcher matcher = CMGL_INDEX_PATTERN.matcher(response);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }

        return indexes;
    }
    
    /**
     * Deletes the SMS at the specified index from modem memory.
     */
    public void deleteSms(int index) {
        try {
            atClient.sendAndWait("AT+CMGD=" + index);
            log.info("Deleted SMS at index {}.", index);
        } catch (Exception e) {
            log.warn("Could not delete SMS at index {}: {}", index, e.getMessage());
        }
    }
}
