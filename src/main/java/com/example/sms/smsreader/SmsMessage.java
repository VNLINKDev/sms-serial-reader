package com.example.sms.smsreader;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * Minimal SMS model: only required fields
 */
@Data
public final class SmsMessage {

    private final int index;
    private final String transactionId;
    private final String otp;
    private final OffsetDateTime timestamp;

    public SmsMessage(int index, String transactionId, String otp, OffsetDateTime timestamp) {
        this.index = index;
        this.transactionId = transactionId;
        this.otp = otp;
        this.timestamp = timestamp;
    }

    public int getIndex() {
        return index;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getOtp() {
        return otp;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "SmsMessage{" +
                "index=" + index +
                ", transactionId='" + transactionId + '\'' +
                ", otp='" + otp + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}