package com.example.sms.exception;

/**
 * Được ném khi tin nhắn SMS được đọc thành công nhưng không chứa OTP
 * (không khớp với pattern tin nhắn OTP được cấu hình).
 */
public class NonOtpSmsException extends RuntimeException {

    private final String smsContent;

    public NonOtpSmsException(String message) {
        this(message, null);
    }

    public NonOtpSmsException(String message, String smsContent) {
        super(message);
        this.smsContent = smsContent;
    }

    public String getSmsContent() {
        return smsContent;
    }
}
