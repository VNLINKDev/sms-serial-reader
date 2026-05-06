package com.example.sms.exception;

/** Thrown when the modem does not respond within the allotted timeout. */
public class ModemTimeoutException extends RuntimeException {
    public ModemTimeoutException(String command, int timeoutMs) {
        super("Modem did not respond to '" + command + "' within " + timeoutMs + " ms");
    }
}
