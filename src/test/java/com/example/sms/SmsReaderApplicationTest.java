package com.example.sms;

import com.example.sms.app.SmsReaderRuntime;
import com.example.sms.redis.RedisPublisher;
import com.example.sms.serial.SerialPortManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = "sms.serial.port=TEST")
class SmsReaderApplicationTest {

    @MockBean
    private SmsReaderRuntime smsReaderRuntime;

    @MockBean
    private SerialPortManager serialPortManager;

    @MockBean
    private RedisPublisher redisPublisher;

    @Test
    void contextLoads() {
    }
}
