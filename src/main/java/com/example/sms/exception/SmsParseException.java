package com.example.sms.exception;

/** Được ném khi không thể phân tích phản hồi AT+CMGR thành SmsMessage. */
public class SmsParseException extends RuntimeException {
    public SmsParseException(String message) { super(message); }
    public SmsParseException(String message, Throwable cause) { super(message, cause); }
}
