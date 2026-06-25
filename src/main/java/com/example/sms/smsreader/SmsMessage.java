package com.example.sms.smsreader;

import java.time.OffsetDateTime;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Model SMS tối giản: chỉ gồm các trường bắt buộc.
 */
@Data
@RequiredArgsConstructor
public final class SmsMessage {

    private final int index;
    private final String transactionId;
    private final String otp;
    private final OffsetDateTime timestamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SmsMessage)) {
            return false;
        }
        SmsMessage that = (SmsMessage) o;
        return index == that.index
                && (transactionId == null ? that.transactionId == null : transactionId.equals(that.transactionId))
                && (otp == null ? that.otp == null : otp.equals(that.otp))
                && (timestamp == null ? that.timestamp == null : timestamp.equals(that.timestamp));
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(index);
        result = 31 * result + (transactionId == null ? 0 : transactionId.hashCode());
        result = 31 * result + (otp == null ? 0 : otp.hashCode());
        result = 31 * result + (timestamp == null ? 0 : timestamp.hashCode());
        return result;
    }
}
