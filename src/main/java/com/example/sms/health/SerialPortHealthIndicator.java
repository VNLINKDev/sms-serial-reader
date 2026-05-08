package com.example.sms.health;

import com.example.sms.config.AppConfig;
import com.example.sms.serial.SerialPortManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SerialPortHealthIndicator implements HealthIndicator {

    private final SerialPortManager serialPortManager;
    private final AppConfig appConfig;

    @Override
    public Health health() {
        boolean open = serialPortManager.isOpen();
        Health.Builder builder = open ? Health.up() : Health.down();
        return builder
                .withDetail("port", appConfig.getSerialPort())
                .withDetail("open", open)
                .build();
    }
}
