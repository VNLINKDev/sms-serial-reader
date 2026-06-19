package com.example.sms.exception;

/**
 * Được ném khi tin nhắn SMS được đọc thành công nhưng không chứa OTP
 * (không khớp với pattern tin nhắn OTP được cấu hình).
 */
public class NonOtpSmsException extends RuntimeException {
    public NonOtpSmsException(String message) {
        super(message);
    }
}
