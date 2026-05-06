package com.example.sms.exception;

/** Thrown when an AT+CMGR response cannot be parsed into an SmsMessage. */
public class SmsParseException extends RuntimeException {
    public SmsParseException(String message) { super(message); }
    public SmsParseException(String message, Throwable cause) { super(message, cause); }
}
