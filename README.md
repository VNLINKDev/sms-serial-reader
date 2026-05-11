# SMS Serial Reader

Service Java Spring Boot đọc SMS từ GSM modem qua serial port, parse thông tin giao dịch/OTP/timestamp và ghi dữ liệu JSON sang Redis.

## 1. Tổng Quan Dự Án

Ứng dụng chạy dạng monolith nhỏ:

```text
GSM modem -> SerialPortManager -> RxBuffer -> SmsReaderRuntime -> SmsService/SmsParser -> RedisPublisher -> Redis
```

Service không expose business API. Endpoint HTTP hiện dùng cho vận hành qua Spring Boot Actuator:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

## 2. Tech Stack

| Thành phần | Version/ghi chú |
|---|---|
| Java | 21 LTS |
| Spring Boot | 3.3.7 |
| Build tool | Maven |
| Serial library | jSerialComm |
| Redis client | Lettuce |
| Monitoring | Spring Boot Actuator, Micrometer Prometheus |
| Logging | Logback rolling file + console |
| Deploy ưu tiên | Docker Compose trên Ubuntu server |

## 3. Cấu Trúc Thư Mục

```text
.
├── .github/workflows/deploy.yml
├── Dockerfile
├── docker-compose.yml
├── docker-compose.staging.yml
├── docker-compose.prod.yml
├── nginx/default.conf
├── scripts/
│   ├── deploy.sh
│   ├── rollback.sh
│   ├── restart.sh
│   ├── backup-db.sh
│   └── restore-db.sh
├── docs/operations-sop.md
├── src/main/java/com/example/sms/
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   ├── application-dev.yml
│   ├── application-staging.yml
│   ├── application-prod.yml
│   └── logback-spring.xml
└── pom.xml
```

## 4. Yêu Cầu Môi Trường

Local:

- JDK 21
- Maven 3.9+
- Redis 6/7
- GSM modem hỗ trợ AT command SMS text mode

Server staging/production:

- Ubuntu 22.04/24.04 LTS
- Docker Engine + Docker Compose plugin
- User deploy có quyền chạy Docker
- GSM modem được nhận dạng dạng `/dev/ttyUSB0` hoặc `/dev/ttyACM0`
- Redis nội bộ, không public Internet
- Nginx nếu cần reverse proxy/TLS

## 5. Cách Chạy Local

Tạo file cấu hình:

```bash
cp .env.example .env
```

Chỉnh `.env` cho máy local. Trên Windows chạy trực tiếp bằng Java thường dùng:

```env
SPRING_PROFILES_ACTIVE=local
SERIAL_PORT=COM9
REDIS_HOST=127.0.0.1
REDIS_MODE=VALUE
```

Build và chạy:

```bash
mvn clean package
java -jar target/sms-serial-reader-1.0.0.jar
```

Hoặc chạy bằng Maven:

```bash
mvn spring-boot:run
```

Kiểm tra health:

```bash
curl http://localhost:8080/actuator/health
```

## 6. Cấu Hình Biến Môi Trường

| Biến | Mặc định | Mô tả |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `local`, `dev`, `staging`, `prod` |
| `SERVER_PORT` | `8080` | HTTP port |
| `SERIAL_DEVICE` | `/dev/ttyUSB0` | Device host khi chạy Docker |
| `SERIAL_PORT` | `COM9` local, `/dev/ttyUSB0` Docker | Port app dùng để mở modem |
| `BAUD_RATE` | `115200` | Baud rate modem |
| `REDIS_HOST` | `127.0.0.1`/`redis` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | rỗng | Redis AUTH, để trong `.env`/secret |
| `REDIS_DATABASE` | `0` | Redis database index |
| `REDIS_QUEUE_NAME` | `sms:incoming` | Redis key/channel |
| `REDIS_MODE` | `VALUE` | `VALUE` dùng `SET`, `LIST` dùng `RPUSH` |
| `REDIS_PUBLISH_RETRIES` | `3` | Số lần retry publish |
| `DELETE_SMS_AFTER_READ` | `false` local/staging, `true` prod | Xóa SMS khỏi modem sau khi đọc |
| `UNREAD_POLL_INTERVAL_MS` | `60000` | Chu kỳ scan SMS chưa đọc |
| `LOG_PATH` | `/app/logs` Docker, `logs` local | Thư mục log |
| `LOG_LEVEL` | `INFO` | Log level root |

Không commit `.env` thật vào git.

## 7. Cách Build Project

```bash
mvn clean verify
mvn clean package
```

Artifact:

```text
target/sms-serial-reader-1.0.0.jar
```

Build Docker image:

```bash
docker build -t sms-serial-reader:1.0.0 .
```

## 8. Cách Chạy Test

```bash
mvn test
mvn clean verify
```

## 9. Deploy Bằng Docker Compose

Trên server:

```bash
git clone <repository-url> sms-serial-reader
cd sms-serial-reader
cp .env.example .env
chmod +x scripts/*.sh *.sh
```

Cập nhật `.env`:

```env
SPRING_PROFILES_ACTIVE=staging
SERIAL_DEVICE=/dev/ttyUSB0
SERIAL_PORT=/dev/ttyUSB0
REDIS_PASSWORD=<set-strong-password>
REDIS_MODE=VALUE
```

Kiểm tra modem:

```bash
ls -l /dev/ttyUSB* /dev/ttyACM*
sudo usermod -aG dialout $USER
```

Deploy staging:

```bash
./scripts/deploy.sh staging
```

Deploy production:

```bash
./scripts/deploy.sh production
```

Xem log:

```bash
docker compose logs -f sms-reader
tail -f logs/sms-reader.log
```

## 10. Deploy Production

Checklist tối thiểu:

- `mvn clean verify` pass.
- `.env` production đã review.
- `SERIAL_PORT` đúng với server.
- Redis production có password/ACL và không public.
- Có backup Redis trước deploy nếu đang lưu dữ liệu quan trọng.
- Có image/version rollback.
- Có người kiểm tra SMS test end-to-end.

Lệnh:

```bash
./scripts/backup-db.sh
./scripts/deploy.sh production
curl -f http://127.0.0.1:8080/actuator/health
```

Nếu dùng Nginx/TLS, mount certificate vào `nginx/certs/fullchain.pem` và `nginx/certs/privkey.pem`, sau đó chạy compose prod.

## 11. Rollback

Rollback về image trước đó:

```bash
./scripts/rollback.sh production
```

Sau rollback:

```bash
curl -f http://127.0.0.1:8080/actuator/health
docker compose logs --tail=200 sms-reader
```

## 12. Backup/Restore Database

Project hiện chưa dùng relational database. Script `backup-db.sh` và `restore-db.sh` đang backup/restore Redis snapshot vì Redis là datastore/message backend duy nhất hiện tại.

Backup:

```bash
./scripts/backup-db.sh
```

Restore:

```bash
CONFIRM_RESTORE=yes ./scripts/restore-db.sh ./backups/redis-YYYYMMDD-HHMMSS.rdb
```

Nếu sau này bổ sung PostgreSQL/MySQL, thêm Flyway dependency và tạo migration trong `src/main/resources/db/migration`.

## 13. Troubleshooting

Health DOWN:

```bash
curl -s http://127.0.0.1:8080/actuator/health
docker compose logs --tail=200 sms-reader
```

Không mở được serial port:

```bash
ls -l /dev/ttyUSB0
dmesg | tail -n 100
sudo usermod -aG dialout $USER
```

Redis lỗi:

```bash
docker exec -it sms-reader-redis redis-cli ping
docker exec -it sms-reader-redis redis-cli GET sms:incoming
```

Không thấy SMS mới:

- Kiểm tra modem có sóng/SIM.
- Kiểm tra `SERIAL_PORT`.
- Kiểm tra log modem init.
- Kiểm tra `REDIS_MODE`: `VALUE` chỉ giữ SMS mới nhất bằng `SET`; `PUBSUB` chỉ publish realtime.

## 14. Useful Commands

```bash
mvn clean verify
docker compose up -d --build
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose ps
docker compose logs -f sms-reader
docker compose restart sms-reader
curl -f http://127.0.0.1:8080/actuator/health
redis-cli -h 127.0.0.1 -p 6379 GET sms:incoming
./scripts/backup-db.sh
./scripts/rollback.sh production
```
