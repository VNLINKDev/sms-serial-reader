package com.example.sms.smsreader;

import java.time.OffsetDateTime;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Minimal SMS model: only required fields
 */
@Data
@RequiredArgsConstructor
public final class SmsMessage {

    private final int index;
    private final String transactionId;
    private final String otp;
    private final OffsetDateTime timestamp;
}
