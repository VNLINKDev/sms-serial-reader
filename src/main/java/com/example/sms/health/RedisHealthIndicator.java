package com.example.sms.health;

import com.example.sms.redis.RedisPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator cho Redis integration.
 *
 * Endpoint actuator dùng bean này để xác nhận connection Redis còn phản hồi
 * PING. Đây là health check hạ tầng, không xác nhận consumer phía sau đã nhận
 * hoặc xử lý SMS.
 */
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisPublisher redisPublisher;

    @Override
    public Health health() {
        try {
            // NOTE: Health check dùng chung Redis connection với publisher; nếu transaction Redis phức tạp hơn, nên tách connection health riêng.
            String pong = redisPublisher.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up().withDetail("ping", pong).build();
            }
            return Health.down().withDetail("ping", pong).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
