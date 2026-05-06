package com.example.sms.exception;

/** Thrown when publishing to Redis fails even after the configured retries. */
public class RedisPublishException extends RuntimeException {
    public RedisPublishException(String message, Throwable cause) { super(message, cause); }
}
