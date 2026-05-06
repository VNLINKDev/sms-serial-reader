package com.example.sms.exception;

/** Thrown when a serial port cannot be opened or used. */
public class SerialPortException extends RuntimeException {
    public SerialPortException(String message) { super(message); }
    public SerialPortException(String message, Throwable cause) { super(message, cause); }
}
