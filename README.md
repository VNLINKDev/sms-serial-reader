# SMS Serial Reader

Service Java SE thuần (Vanilla Java) đọc SMS từ GSM modem qua serial port, parse thông tin giao dịch/OTP/timestamp và ghi dữ liệu JSON sang Redis, đồng thời gửi thông báo qua Telegram.

## 1. Tổng Quan Dự Án

Ứng dụng chạy dạng monolith nhỏ:

```text
GSM modem -> SerialPortManager -> RxBuffer -> SmsReaderRuntime -> SmsService/SmsParser -> RedisPublisher -> Redis
                                                                                     -> TelegramNotifier -> Telegram
```

Service không expose HTTP API và chạy ẩn dạng tiến trình nền (background process/daemon).

## 2. Tech Stack

| Thành phần | Version/ghi chú |
|---|---|
| Java | 11 LTS (hoặc cao hơn) |
| Build tool | Maven |
| Serial library | jSerialComm |
| Redis client | Lettuce |
| Logging | Logback rolling file + console |

## 3. Cấu Trúc Thư Mục

```text
.
├── src/main/java/com/example/sms/
├── src/main/resources/
│   └── logback.xml
├── start.sh
├── stop.sh
├── restart.sh
└── pom.xml
```

## 4. Yêu Cầu Môi Trường

- JDK 11 (hoặc cao hơn)
- Maven 3.6+
- Redis 6/7
- GSM modem hỗ trợ AT command SMS text mode kết nối qua cổng Serial/USB (ví dụ: `/dev/ttyUSB0` trên Linux hoặc `COM9` trên Windows).

## 5. Cách Chạy Local / Server trực tiếp

Tạo file cấu hình `.env` bằng cách copy từ file mẫu:

```bash
cp .env.example .env
```

Chỉnh sửa nội dung `.env` cho phù hợp:

```env
SPRING_PROFILES_ACTIVE=local
SERIAL_PORT=COM9
REDIS_HOST=127.0.0.1
REDIS_MODE=VALUE
```

### Build dự án:

```bash
mvn clean package
```

Sau khi build thành công, file JAR sẽ được tạo tại `target/sms-serial-reader-1.0.0.jar`.

### Chạy ứng dụng bằng script:

- **Khởi động ứng dụng**: `bash start.sh` (chạy ngầm dùng `nohup`, ghi log ra thư mục `logs/app.log`)
- **Dừng ứng dụng**: `bash stop.sh`
- **Khởi động lại**: `bash restart.sh`

Hoặc chạy trực tiếp bằng dòng lệnh:

```bash
java -jar target/sms-serial-reader-1.0.0.jar
```

## 6. Cấu Hình Biến Môi Trường

| Biến | Mặc định | Mô tả |
|---|---|---|
| `SERIAL_PORT` | `COM9` | Port app dùng để kết nối tới GSM modem |
| `BAUD_RATE` | `115200` | Baud rate modem |
| `REDIS_HOST` | `127.0.0.1` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | rỗng | Redis AUTH password |
| `REDIS_DATABASE` | `0` | Redis database index |
| `REDIS_QUEUE_NAME` | `sms:incoming` | Redis key/channel |
| `REDIS_MODE` | `VALUE` | `VALUE` dùng `SET`, `LIST` dùng `RPUSH` |
| `REDIS_PUBLISH_RETRIES` | `3` | Số lần retry publish |
| `DELETE_SMS_AFTER_READ` | `false` | Xóa SMS khỏi modem sau khi đọc |
| `UNREAD_POLL_INTERVAL_MS` | `60000` | Chu kỳ scan SMS chưa đọc |
| `TELEGRAM_BOT_TOKEN` | rỗng | Token của Telegram bot |
| `TELEGRAM_CHAT_ID` | rỗng | Chat ID nhận thông báo OTP |

## 7. Cách Chạy Test

```bash
mvn test
```

