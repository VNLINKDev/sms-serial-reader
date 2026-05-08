package com.example.sms.exception;

/** Được ném khi modem không phản hồi trong thời gian chờ cho phép. */
public class ModemTimeoutException extends RuntimeException {
    public ModemTimeoutException(String command, int timeoutMs) {
        super("Modem did not respond to '" + command + "' within " + timeoutMs + " ms");
    }
}
