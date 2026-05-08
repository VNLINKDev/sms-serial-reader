package com.example.sms.modem;

import com.example.sms.serial.AtCommandClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gửi chuỗi lệnh AT khởi tạo chuẩn để đưa modem về trạng thái đã biết
 * và sẵn sàng hoạt động.
 */
@Component
@RequiredArgsConstructor
public class ModemInitializer {

    private static final Logger log = LoggerFactory.getLogger(ModemInitializer.class);

    private final AtCommandClient atClient;

    /**
     * Chạy toàn bộ chuỗi khởi tạo. Ném lỗi khi modem báo lỗi hoặc hết thời gian
     * chờ để ứng dụng dừng sớm nếu modem không phản hồi đúng cách.
     */
    public void initialize() {
        log.info("Initialising modem...");

        send("AT");                        // kiểm tra kết nối cơ bản
        send("ATE0");                      // tắt echo lệnh
        send("AT+CMGF=1");                 // chế độ text
        send("AT+CSCS=\"GSM\"");           // bộ ký tự GSM
        send("AT+CNMI=2,1,0,0,0");        // thông báo SMS mới qua +CMTI
        send("AT+CPIN?");                  // trạng thái SIM
        send("AT+CSQ");                    // chất lượng tín hiệu

        log.info("Modem initialised successfully. Waiting for incoming SMS...");
    }

    private void send(String command) {
        String response = atClient.sendAndWait(command);
        log.debug("CMD={} => {}", command, response.trim());
    }
}
