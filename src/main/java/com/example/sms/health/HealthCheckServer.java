package com.example.sms.health;

import com.example.sms.config.AppConfig;
import com.example.sms.serial.SerialPortManager;
import com.example.sms.redis.RedisPublisher;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Web server siêu nhẹ dùng HttpServer mặc định của JDK để phục vụ endpoint
 * `/actuator/health`
 * tương thích hoàn toàn với định dạng phản hồi của Spring Boot Actuator.
 */
public class HealthCheckServer {
    private final SerialPortManager portManager;
    private final RedisPublisher redisPublisher;
    private final AppConfig config;
    private HttpServer server;

    public HealthCheckServer(SerialPortManager portManager,
            RedisPublisher redisPublisher, AppConfig config) {
        this.portManager = portManager;
        this.redisPublisher = redisPublisher;
        this.config = config;
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/actuator/health", new HealthHandler());
            server.setExecutor(null); // Sử dụng default executor
            server.start();
            System.out.println("Health check HTTP server started on port " + port);
        } catch (IOException e) {
            System.err.println("Failed to start health check server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("Health check HTTP server stopped.");
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            boolean open = portManager.isOpen();
            boolean redisOk = false;
            try {
                redisOk = "PONG".equalsIgnoreCase(redisPublisher.ping());
            } catch (Exception ignored) {
            }

            int statusCode = (open && redisOk) ? 200 : 503;
            String statusStr = (open && redisOk) ? "UP" : "DOWN";

            // Xây dựng chuỗi JSON giống hệt định dạng Spring Boot Actuator
            String response = "{"
                    + "\"status\":\"" + statusStr + "\","
                    + "\"components\":{"
                    + "\"serial\":{"
                    + "\"status\":\"" + (open ? "UP" : "DOWN") + "\","
                    + "\"details\":{\"port\":\"" + config.getSerialPort() + "\"}"
                    + "},"
                    + "\"redis\":{"
                    + "\"status\":\"" + (redisOk ? "UP" : "DOWN") + "\""
                    + "}"
                    + "}"
                    + "}";

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
