package com.example.sms.modem;

import com.example.sms.serial.AtCommandClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gửi chuỗi lệnh AT khởi tạo chuẩn để đưa modem về trạng thái đã biết
 * và sẵn sàng hoạt động.
 *
 * Lớp reader Service đã start để bảo đảm response init không bị mất khỏi buffer.
 */
@RequiredArgsConstructor
public class ModemInitializer {

    private static final Logger log = LoggerFactory.getLogger(ModemInitializer.class);

    private final AtCommandClient atClient;

    /**
     * Chạy toàn bộ chuỗi init theo thứ tự phụ thuộc.
     *
     * Các lệnh đầu tiên xác nhận đường truyền và tắt echo để response dễ parse.
     * Sau đó modem được đưa vào SMS text mode, charset GSM và bật notification
     * {@code +CMTI}. Nếu bất kỳ command nào timeout/lỗi, exception được bubble up
     * để application fail fast thay vì chạy ở trạng thái modem chưa xác định.
     */
    public void initialize() {
        log.info("Đang khởi tạo modem...");

        send("AT");                        // kiểm tra kết nối cơ bản
        send("ATE0");                      // tắt echo lệnh
        send("AT+CMGF=1");                 // chế độ text
        send("AT+CSCS=\"GSM\"");           // bộ ký tự GSM
        send("AT+CNMI=2,1,0,0,0");        // thông báo SMS mới qua +CMTI
        send("AT+CPIN?");                  // trạng thái SIM
        send("AT+CSQ");                    // chất lượng tín hiệu

        log.info("Modem đã khởi tạo thành công. Đang chờ SMS đến...");
    }

    private void send(String command) {
        String response = atClient.sendAndWait(command);
        log.debug("CMD={} => {}", command, response.trim());
    }
}
