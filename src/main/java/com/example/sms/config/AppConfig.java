package com.example.sms.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "sms")
public class AppConfig {

    @Valid
    private final Serial serial = new Serial();

    @Valid
    private final Redis redis = new Redis();

    @Valid
    private final Behavior behavior = new Behavior();

    public enum RedisMode { LIST, PUBSUB }

    public Serial getSerial() {
        return serial;
    }

    public Redis getRedis() {
        return redis;
    }

    public Behavior getBehavior() {
        return behavior;
    }

    public String getSerialPort() {
        return serial.getPort();
    }

    public int getBaudRate() {
        return serial.getBaudRate();
    }

    public String getRedisHost() {
        return redis.getHost();
    }

    public int getRedisPort() {
        return redis.getPort();
    }

    public String getRedisPassword() {
        return redis.getPassword();
    }

    public int getRedisDatabase() {
        return redis.getDatabase();
    }

    public String getRedisQueueName() {
        return redis.getQueueName();
    }

    public RedisMode getRedisMode() {
        return redis.getMode();
    }

    public boolean isDeleteSmsAfterRead() {
        return behavior.isDeleteSmsAfterRead();
    }

    public int getRedisPublishRetries() {
        return redis.getPublishRetries();
    }

    public static class Serial {
        @NotBlank
        private String port;

        @Min(1)
        private int baudRate = 115200;

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public int getBaudRate() {
            return baudRate;
        }

        public void setBaudRate(int baudRate) {
            this.baudRate = baudRate;
        }
    }

    public static class Redis {
        @NotBlank
        private String host = "127.0.0.1";

        @Min(1)
        private int port = 6379;

        private String password;

        @Min(0)
        private int database = 0;

        @NotBlank
        private String queueName = "sms:incoming";

        private RedisMode mode = RedisMode.LIST;

        @Min(1)
        private int publishRetries = 3;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public RedisMode getMode() {
            return mode;
        }

        public void setMode(RedisMode mode) {
            this.mode = mode;
        }

        public int getPublishRetries() {
            return publishRetries;
        }

        public void setPublishRetries(int publishRetries) {
            this.publishRetries = publishRetries;
        }
    }

    public static class Behavior {
        private boolean deleteSmsAfterRead = false;

        public boolean isDeleteSmsAfterRead() {
            return deleteSmsAfterRead;
        }

        public void setDeleteSmsAfterRead(boolean deleteSmsAfterRead) {
            this.deleteSmsAfterRead = deleteSmsAfterRead;
        }
    }
}
