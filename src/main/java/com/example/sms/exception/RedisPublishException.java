package com.example.sms.exception;

/** Được ném khi gửi dữ liệu sang Redis vẫn thất bại sau số lần thử lại đã cấu hình. */
public class RedisPublishException extends RuntimeException {
    public RedisPublishException(String message, Throwable cause) { super(message, cause); }
}
