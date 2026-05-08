package com.example.sms.exception;

/** Được ném khi không thể mở hoặc sử dụng cổng serial. */
public class SerialPortException extends RuntimeException {
    public SerialPortException(String message) { super(message); }
    public SerialPortException(String message, Throwable cause) { super(message, cause); }
}
