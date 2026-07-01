# SMS Serial Reader — Đọc SMS OTP qua Cổng Serial & Modem GSM

Ứng dụng Java SE thuần (không sử dụng Spring Boot) chạy dưới dạng background service để đọc tin nhắn SMS OTP từ thiết bị Modem GSM kết nối qua cổng Serial (COM port / USB serial), tự động phân tích trích xuất thông tin OTP và đẩy dữ liệu về Redis phục vụ tích hợp hệ thống, đồng thời gửi cảnh báo nhanh qua Telegram.

---

## 📋 Tính Năng Chính

*   **Đọc SMS qua Cổng Serial vật lý:** Kết nối trực tiếp đến Modem GSM thông qua thư viện `jSerialComm` và giao tiếp bằng tập lệnh AT.
*   **Trích xuất OTP tự động:** Sử dụng biểu thức chính quy (Regex) cấu hình động để bóc tách mã giao dịch (Transaction ID) và mã OTP từ nội dung SMS.
*   **Hỗ trợ múi giờ GSM:** Phân tích chính xác định dạng thời gian của mạng viễn thông GSM (Quarter-hour format) và tự động fallback về thời gian hệ thống nếu định dạng thời gian từ modem lỗi.
*   **Tích hợp Redis Pub/Sub & Key-Value:** Đẩy thông tin SMS đã phân tích lên Redis dưới dạng JSON payload với hai chế độ:
    *   **VALUE:** `SET` đè tin nhắn mới nhất vào một key cố định.
    *   **LIST:** `RPUSH` tin nhắn vào danh sách hàng đợi (Queue).
*   **Cơ chế chống trùng lặp & Retry:**
    *   Tự động lọc trùng lặp tin nhắn đã xử lý ở mức RAM (in-memory deduplication) dựa trên ID giao dịch.
    *   Tự động retry gửi dữ liệu lên Redis với cơ chế hoãn luỹ tiến (Exponential Backoff) nếu Redis gặp sự cố tạm thời.
*   **Thông báo Telegram:** Gửi cảnh báo tức thì kèm định dạng HTML (Transaction ID, OTP, Thời gian) tới Telegram Group/Channel thông qua Telegram Bot API.
*   **Quản lý bộ nhớ SIM thông minh:**
    *   Tự động dọn dẹp các SMS không phải là OTP (Non-OTP SMS) để tránh đầy SIM.
    *   Tự động dọn dẹp các SMS OTP cũ dựa trên ngưỡng dung lượng SIM (**Watermark**), chỉ giữ lại một số lượng tin nhắn gần nhất (**Keep Recent**) để hỗ trợ kiểm tra lỗi hoặc retry khi cần.
    *   Hỗ trợ cấu hình xóa SMS ngay sau khi xử lý thành công (`DELETE_SMS_AFTER_READ`).
*   **Khả năng tự phục hồi kết nối (Self-Healing):** Tự động phát hiện lỗi cổng Serial (như rút/cắm lại thiết bị) và thực hiện kết nối lại tuần tự với Exponential Backoff.
*   **Thiết kế luồng an toàn (Thread-safe & Reactive):** Sử dụng RxJava 3 kết hợp với các `SingleThreadExecutor` độc lập cho từng tác vụ (Quét SMS, Gửi Redis, Gửi Telegram, Dọn SIM) đảm bảo việc truy xuất cổng Serial được thực hiện tuần tự, tránh xung đột lệnh AT.

---

## 🛠️ Công Nghệ Sử Dụng (Tech Stack)

*   **Ngôn ngữ:** Java 11 (Java SE thuần)
*   **Hệ thống xây dựng:** Maven (đóng gói Fat JAR bằng `maven-shade-plugin`)
*   **Thư viện cốt lõi:**
    *   `com.fazecast:jSerialComm:2.10.4` — Giao tiếp cổng Serial/UART.
    *   `io.reactivex.rxjava3:rxjava:3.1.10` — Điều phối sự kiện bất đồng bộ theo mô hình phản xạ (Reactive programming).
    *   `io.lettuce:lettuce-core:6.3.2.RELEASE` — Driver kết nối Redis hiệu năng cao, thread-safe.
    *   `com.fasterxml.jackson.core:jackson-databind:2.17.1` — Biến đổi Object sang JSON payload và ngược lại.
    *   `ch.qos.logback:logback-classic:1.5.6` & `org.slf4j:slf4j-api:2.0.13` — Ghi nhật ký hệ thống (Logging).
    *   `org.projectlombok:lombok:1.18.36` — Giảm thiểu mã boilerplate (Getter, Setter, Constructor...).
*   **Thành phần kiểm thử:**
    *   `org.junit.jupiter:junit-jupiter-api` & `engine` (JUnit 5)
    *   `org.mockito:mockito-core` & `junit-jupiter` (Mockito)
    *   `org.awaitility:awaitility` (Hỗ trợ assert bất đồng bộ)

---

## 🖥️ Yêu Cầu Hệ Thống (Prerequisites)

*   **Hệ điều hành:** Linux (khuyến nghị cho môi trường production) hoặc Windows (dùng cho phát triển).
*   **Java:** JDK 11 hoặc mới hơn.
*   **Maven:** Phiên bản 3.6 trở lên.
*   **Redis:** Server Redis đang hoạt động.
*   **Thiết bị phần cứng:** Thiết bị GSM Modem (như USB 3G/4G Huawei, module GSM SIM800/SIM900...) hỗ trợ cổng COM ảo và tập lệnh AT chuẩn.

---

## ⚙️ Cấu Hình (Configuration)

Cấu hình ứng dụng được nạp theo thứ tự ưu tiên: **Biến môi trường hệ thống (System Environment) > File `.env` > Giá trị mặc định (Default values)**.

Hãy sao chép file cấu hình mẫu và điều chỉnh các thông số:
```bash
cp .env.example .env
```

### Các tham số cấu hình chính trong `.env`

| Biến Môi Trường | Giá trị Mặc định | Mô tả |
| :--- | :--- | :--- |
| `SERIAL_PORT` | `COM5` | Đường dẫn/Tên cổng kết nối thiết bị Modem (ví dụ: `COM5` trên Windows hoặc `/dev/ttyUSB0` trên Linux). |
| `BAUD_RATE` | `115200` | Tốc độ truyền dữ liệu của cổng Serial. |
| `REDIS_HOST` | `127.0.0.1` | Địa chỉ IP/Domain của máy chủ Redis. |
| `REDIS_PORT` | `6379` | Cổng kết nối Redis. |
| `REDIS_PASSWORD` | `""` | Mật khẩu truy cập Redis (để trống nếu không dùng). |
| `REDIS_DATABASE` | `0` | Database index sử dụng trong Redis. |
| `REDIS_QUEUE_NAME` | `sms:incoming` | Tên key/danh sách lưu trữ kết quả trên Redis. |
| `REDIS_MODE` | `VALUE` | Chế độ ghi Redis: `VALUE` (lưu đè key) hoặc `LIST` (rpush vào hàng đợi). |
| `REDIS_PUBLISH_RETRIES`| `3` | Số lần thử lại tối đa khi gửi dữ liệu lên Redis thất bại. |
| `REDIS_TIMEOUT_MS` | `10000` | Thời gian timeout kết nối Redis (mili-giây). |
| `DELETE_SMS_AFTER_READ`| `false` | Có xóa SMS khỏi SIM ngay sau khi đọc thành công hay không. |
| `UNREAD_POLL_INTERVAL_MS`| `60000` | Chu kỳ quét kiểm tra tin nhắn mới trên SIM (mili-giây). |
| `SIM_HIGH_WATERMARK` | `20` | Ngưỡng số lượng tin nhắn trên SIM kích hoạt tính năng tự động dọn dẹp. |
| `SIM_KEEP_RECENT` | `5` | Số tin nhắn OTP gần nhất giữ lại trên SIM sau khi thực hiện dọn dẹp. |
| `TELEGRAM_ENABLED` | `false` | Bật (`true`) hoặc tắt (`false`) cảnh báo qua Telegram. |
| `TELEGRAM_BOT_TOKEN` | `""` | Token của Telegram Bot tạo từ `@BotFather`. |
| `TELEGRAM_CHAT_ID` | `""` | ID của Group/Channel Telegram nhận cảnh báo. |
| `SMS_INDEX_CMGL_PATTERN`| `\\+CMGL:\\s*(\\d+),` | Biểu thức Regex để lọc index của tin nhắn từ phản hồi lệnh `AT+CMGL`. |
| `SMS_OTP_PATTERN` | `Ma\\s+giao\\s+dich\\s+(\\d+).*?OTP\\s*:?\\s*(\\d+)` | Biểu thức Regex để trích xuất Mã giao dịch và OTP từ nội dung tin nhắn. |
| `LOG_LEVEL` | `INFO` | Mức độ log ứng dụng (`DEBUG`, `INFO`, `WARN`, `ERROR`). |
| `LOG_PATH` | `./logs` | Đường dẫn lưu thư mục chứa file log. |

---

## 📁 Cấu Trúc Thư Mục (Project Structure)

Dưới đây là cấu trúc các file mã nguồn chính và vai trò của chúng trong hệ thống:

```text
sms-serial-reader/
├── .env.example                 # File mẫu khai báo các biến môi trường
├── pom.xml                      # Cấu hình Maven dependencies và plugins
├── start.sh                     # Script Bash khởi động app chạy ngầm (Linux)
├── stop.sh                      # Script Bash tắt app an toàn (Linux)
├── restart.sh                   # Script Bash khởi động lại nhanh app (Linux)
├── src/
│   ├── main/
│   │   ├── java/com/example/sms/
│   │   │   ├── SmsReaderApplication.java       # Lớp Bootstrap khởi động toàn bộ ứng dụng
│   │   │   ├── app/
│   │   │   │   └── SmsReaderRuntime.java       # Bộ điều phối chạy ngầm chính (Core Orchestrator) dùng RxJava 3
│   │   │   ├── config/
│   │   │   │   ├── AppConfig.java              # Quản lý khai báo biến cấu hình hệ thống
│   │   │   │   └── EnvLoader.java              # Tiện ích đọc và xử lý file .env và system env
│   │   │   ├── exception/                      # Tập hợp các class định nghĩa ngoại lệ đặc thù của hệ thống
│   │   │   ├── modem/
│   │   │   │   └── ModemInitializer.java       # Khởi tạo modem GSM qua lệnh AT (tắt echo, bật text mode, CMTI...)
│   │   │   ├── redis/
│   │   │   │   └── RedisPublisher.java         # Driver kết nối và đẩy JSON SMS lên Redis (mode SET/RPUSH)
│   │   │   ├── serial/
│   │   │   │   ├── SerialPortManager.java      # Quản lý vòng đời mở, cấu hình, đóng và reconnect cổng Serial
│   │   │   │   └── AtCommandClient.java        # Client gửi lệnh AT đồng bộ và xả buffer cũ (drain stale data)
│   │   │   ├── smsreader/
│   │   │   │   ├── SmsMessage.java             # Domain model đại diện cho một tin nhắn SMS OTP
│   │   │   │   ├── SmsParser.java              # Bóc tách cú pháp SMS, trích xuất OTP & xử lý GSM timestamp
│   │   │   │   └── SmsService.java             # Xử lý logic đọc, liệt kê, xóa và dọn dẹp SMS vật lý trên SIM
│   │   │   └── telegram/
│   │   │       └── TelegramNotifier.java       # Client gửi thông tin OTP qua Telegram HTTP API
│   │   └── resources/
│   │       ├── db/migration/README.md          # Hướng dẫn mở rộng cơ sở dữ liệu nếu phát triển sau này
│   │       └── logback.xml                     # Cấu hình định dạng log, rolling file log hàng ngày
│   └── test/
│       └── java/com/example/sms/               # Thư mục kiểm thử (Unit Tests & Integration Tests)
```

---

## 🚀 Hướng Dẫn Cài Đặt & Chạy Ứng Dụng

### 1. Build Dự Án

Sử dụng Maven để biên dịch mã nguồn, chạy kiểm thử và đóng gói ứng dụng thành một file **Fat JAR** (chứa đầy đủ các thư viện phụ thuộc):

```bash
mvn clean package
```

Sau khi chạy xong, file JAR chạy trực tiếp sẽ được tạo ra tại: `target/sms-serial-reader-1.0.0.jar`.

### 2. Chạy Ứng Dụng Trong Môi Trường Phát Triển (Dev/Local)

Sau khi đã hoàn thiện file cấu hình `.env` ở thư mục gốc:

```bash
# Di chuyển file jar ra ngoài thư mục gốc (hoặc chạy trực tiếp từ target)
java -jar target/sms-serial-reader-1.0.0.jar
```

### 3. Vận Hành Trên Môi Trường Production (Linux Daemon)

Hệ thống cung cấp sẵn các script bash hỗ trợ quản lý ứng dụng chạy ngầm (background process) bằng PID và `nohup`:

*   **Khởi động dịch vụ:**
    ```bash
    ./start.sh
    ```
    *Script sẽ tự động chuẩn hóa định dạng dòng (CRLF to LF) của `.env`, nạp cấu hình, khởi chạy app chạy ngầm, ghi file `app.pid` và theo dõi log khởi động trong 5 giây đầu để phát hiện sự cố.*

*   **Dừng dịch vụ:**
    ```bash
    ./stop.sh
    ```
    *Dịch vụ sẽ gửi tín hiệu `SIGTERM` đến ứng dụng và chờ tối đa 15 giây để ứng dụng giải phóng cổng Serial và kết nối Redis một cách an toàn (Graceful Shutdown). Nếu quá thời gian, script sẽ gửi lệnh `SIGKILL` để buộc dừng.*

*   **Khởi động lại dịch vụ:**
    ```bash
    ./restart.sh
    ```

*   **Xem log ứng dụng:**
    ```bash
    # Xem log hoạt động chính của ứng dụng
    tail -f logs/sms-reader.log
    
    # Xem log khởi động console/stderr
    tail -f logs/app.log
    ```

---

## 🔌 API & Định Dạng Dữ Liệu Đầu Ra

Dự án này hoạt động như một background worker trung gian nên không cung cấp các HTTP API endpoint. Thay vào đó, dữ liệu đầu ra được đồng bộ sang Redis dưới dạng JSON String.

### Định dạng JSON payload đẩy lên Redis:

Khi nhận được tin nhắn SMS OTP hợp lệ, ứng dụng sẽ thực hiện ghi nhận dữ liệu vào Redis (dưới dạng key `VALUE` hoặc đẩy vào `LIST` theo cấu hình `REDIS_QUEUE_NAME`):

```json
{
  "index": 1,
  "transactionId": "123456",
  "otp": "987654",
  "timestamp": "2026-06-24T14:30:00+07:00"
}
```

*Ý nghĩa các trường:*
*   `index`: Chỉ số lưu trữ vật lý của tin nhắn trên bộ nhớ SIM.
*   `transactionId`: Mã giao dịch trích xuất được từ nội dung SMS.
*   `otp`: Mã OTP tương ứng.
*   `timestamp`: Thời điểm nhận tin nhắn (đã chuẩn hóa theo ISO 8601 kèm múi giờ chính xác).

---

## 🧪 Kiểm Thử (Testing)

Dự án sử dụng JUnit 5 kết hợp Mockito để viết kiểm thử đơn vị độc lập và Awaitility để kiểm thử luồng bất đồng bộ:

Chạy toàn bộ các test case có sẵn:
```bash
mvn test
```

---

## 🤝 Đóng Góp (Contributing)

1. Tạo nhánh phát triển mới từ `main` (`git checkout -b feature/AmazingFeature`).
2. Thực hiện các chỉnh sửa, tuân thủ Java coding convention và viết bổ sung unit test nếu có logic mới.
3. Chạy kiểm tra build và test cục bộ (`mvn clean test`).
4. Commit các thay đổi (`git commit -m 'Add some AmazingFeature'`).
5. Đẩy nhánh lên remote repository (`git push origin feature/AmazingFeature`).
6. Tạo một Pull Request để kiểm duyệt.

