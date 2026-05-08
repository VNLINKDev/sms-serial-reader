package com.example.sms.health;

import com.example.sms.redis.RedisPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisPublisher redisPublisher;

    @Override
    public Health health() {
        try {
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
