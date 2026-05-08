# Operations SOP - SMS Serial Reader

Phiên bản: 2.0  
Ngày cập nhật: 2026-05-07  
Phạm vi: vận hành service Java Spring Boot đọc SMS từ GSM modem và publish dữ liệu sang Redis trên local/dev/staging/production.

## 1. Tổng Quan Deployment Architecture

```text
Internet/Internal network
        |
      Nginx
        |
Spring Boot sms-reader container
        |
  +-----+------+
  |            |
GSM modem   Redis
/dev/ttyUSB0  redis:6379
```

Thành phần:

| Thành phần | Vai trò |
|---|---|
| `sms-reader` | Spring Boot app đọc serial, parse SMS và ghi Redis |
| Redis | Lưu SMS mới nhất bằng `SET` khi `REDIS_MODE=VALUE`, hoặc channel khi `PUBSUB` |
| Nginx | Reverse proxy, TLS, giới hạn actuator |
| Docker Compose | Quản lý stack staging/production |
| Actuator | Health/metrics/info |

Assumption vận hành: production chạy trên Ubuntu server có quyền truy cập USB serial modem trực tiếp qua `/dev/ttyUSB0` hoặc `/dev/ttyACM0`.

## 2. Server Requirements

| Hạng mục | Khuyến nghị |
|---|---|
| OS | Ubuntu 22.04/24.04 LTS |
| CPU | 1 vCPU trở lên |
| RAM | 1 GB tối thiểu, 2 GB khuyến nghị |
| Disk | 20 GB+, có log rotation |
| Runtime | Docker Engine + Docker Compose plugin |
| Network | Redis không public Internet, chỉ app/internal access |
| Hardware | GSM modem/SIM ổn định, USB port có nguồn tốt |

Setup quyền serial:

```bash
sudo usermod -aG dialout $USER
ls -l /dev/ttyUSB* /dev/ttyACM*
```

## 3. Environment Variables

| Biến | Required | Ghi chú |
|---|---:|---|
| `SPRING_PROFILES_ACTIVE` | yes | `local`, `dev`, `staging`, `prod` |
| `SERVER_PORT` | no | Mặc định `8080` |
| `SERIAL_DEVICE` | yes Docker | Device trên host |
| `SERIAL_PORT` | yes | Port app mở trong container |
| `BAUD_RATE` | no | Mặc định `115200` |
| `REDIS_HOST` | yes | `redis` nếu dùng compose |
| `REDIS_PORT` | yes | Mặc định `6379` |
| `REDIS_PASSWORD` | staging/prod | Secret, không commit |
| `REDIS_DATABASE` | no | Tách DB theo môi trường |
| `REDIS_QUEUE_NAME` | yes | Key/channel |
| `REDIS_MODE` | yes | `VALUE` hoặc `PUBSUB` |
| `DELETE_SMS_AFTER_READ` | no | Production thường `true` sau khi đã kiểm thử |
| `UNREAD_POLL_INTERVAL_MS` | no | Không đặt quá thấp |
| `LOG_PATH` | no | `/app/logs` trong Docker |

## 4. Deployment Flow

1. Developer merge code vào branch release/main.
2. CI chạy `mvn clean verify`.
3. CI build Docker image.
4. CI push image lên registry nếu dùng registry.
5. Server pull image hoặc build image tại server.
6. Docker Compose recreate container.
7. Health check `/actuator/health`.
8. Gửi SMS test và xác nhận Redis payload.
9. Nếu health fail, chạy rollback.

Deploy thủ công:

```bash
cp .env.example .env
chmod +x scripts/*.sh *.sh
vim .env
./scripts/deploy.sh staging
./scripts/deploy.sh production
```

## 5. Production Deploy Checklist

- [ ] Xác định commit/tag/image cần deploy.
- [ ] `mvn clean verify` pass.
- [ ] `.env` production đã review bởi dev + DevOps.
- [ ] `REDIS_PASSWORD` không rỗng nếu Redis do compose quản lý.
- [ ] Redis không mở ra Internet.
- [ ] `SERIAL_PORT` đúng với host production.
- [ ] Modem/SIM có sóng, nhận SMS test được.
- [ ] Backup Redis trước deploy nếu dữ liệu hiện tại cần giữ.
- [ ] Có `.previous-image` hoặc image rollback.
- [ ] Maintenance window đã thông báo nếu hệ thống xử lý OTP quan trọng.

## 6. Post-Deploy Checklist

- [ ] `docker compose ps` hiển thị container healthy.
- [ ] `curl -f http://127.0.0.1:8080/actuator/health` trả `UP`.
- [ ] Log không có lỗi serial/Redis liên tục.
- [ ] Gửi SMS test thành công.
- [ ] Redis có payload đúng contract.
- [ ] Downstream consumer nhận được dữ liệu.
- [ ] Nginx/TLS hoạt động nếu public qua reverse proxy.
- [ ] Dashboard/alert không báo lỗi trong 10-15 phút đầu.

## 7. Rollback Procedure

Điều kiện rollback:

- Health check không `UP` sau deploy.
- App không mở được serial port.
- Redis publish fail liên tục.
- Contract payload sai.
- CPU/memory/log error tăng bất thường.

Lệnh:

```bash
./scripts/rollback.sh production
curl -f http://127.0.0.1:8080/actuator/health
docker compose logs --tail=200 sms-reader
```

Sau rollback cần ghi nhận:

- Version lỗi.
- Thời điểm deploy/rollback.
- Triệu chứng.
- Dữ liệu SMS có bị ảnh hưởng không.
- Hành động phòng ngừa.

## 8. Backup & Restore Procedure

Hiện project chưa có relational database. Redis là datastore/message backend duy nhất.

Backup Redis:

```bash
./scripts/backup-db.sh
ls -lh backups/
```

Restore Redis:

```bash
CONFIRM_RESTORE=yes ./scripts/restore-db.sh ./backups/redis-YYYYMMDD-HHMMSS.rdb
```

Quy tắc:

- Không restore production nếu chưa xác nhận downtime/impact.
- Luôn giữ backup hiện tại trước khi restore bản cũ.
- Sau restore, kiểm tra Redis `PING` và app health.

Khi bổ sung PostgreSQL/MySQL:

- Dùng Flyway trong `src/main/resources/db/migration`.
- Migration phải chạy staging trước production.
- Không sửa migration đã chạy.
- Backup DB trước migration destructive.

## 9. Monitoring & Alerting

Endpoint:

```text
/actuator/health
/actuator/prometheus
/actuator/info
```

Alert khuyến nghị:

| Alert | Điều kiện |
|---|---|
| ServiceDown | Health fail > 1 phút |
| RedisDown | Redis health down |
| SerialPortDown | Serial health down |
| NoSmsFreshness | Không có SMS mới quá SLA nghiệp vụ |
| HighMemory | JVM/container memory > 85% trong 10 phút |
| DiskAlmostFull | Disk log > 85% |
| ErrorSpike | Log ERROR tăng bất thường |

## 10. Log Checking Guide

Log path:

```text
logs/sms-reader.log
logs/sms-reader.YYYY-MM-DD.N.log.gz
```

Lệnh thường dùng:

```bash
docker compose logs -f sms-reader
docker compose logs --tail=200 sms-reader
tail -f logs/sms-reader.log
grep -i "error\|warn\|timeout\|failed" logs/sms-reader.log
```

Không log secret. Hạn chế log OTP đầy đủ ở production nếu không có yêu cầu audit rõ ràng.

## 11. Common Incidents & Resolution

| Sự cố | Kiểm tra | Xử lý |
|---|---|---|
| Health DOWN | `curl /actuator/health`, logs | Xem component Redis/serial nào DOWN, restart nếu cần |
| Không mở được modem | `ls -l /dev/ttyUSB0`, `dmesg` | Sửa `SERIAL_PORT`, quyền `dialout`, cắm lại modem |
| Redis fail | `redis-cli ping`, container logs | Kiểm tra password, network, memory Redis |
| Không có SMS mới | modem, log init, Redis key | Gửi SMS test, restart modem/app nếu modem treo |
| Parse fail | log `SmsParseException` | Bổ sung test và regex parser cho format SMS mới |
| Log đầy disk | `du -sh logs` | Xóa archive cũ theo policy, tăng disk, kiểm tra lỗi lặp |

## 12. Security Checklist

- [ ] `.env` permission hạn chế, không commit git.
- [ ] Redis có password/ACL trên staging/production.
- [ ] Redis bind nội bộ, không public.
- [ ] Chỉ expose `/actuator/health` qua Nginx nếu cần.
- [ ] `/actuator/prometheus` chỉ cho network monitoring.
- [ ] TLS bật ở Nginx/public endpoint.
- [ ] Container không chạy root.
- [ ] Dependency scan định kỳ.
- [ ] Không hard-code secret trong source/image/log.

## 13. Maintenance Routine

Hàng ngày:

- Kiểm tra health.
- Kiểm tra log lỗi.
- Kiểm tra có SMS mới theo nghiệp vụ.

Hàng tuần:

- Kiểm tra dung lượng log/backups.
- Kiểm tra modem/SIM/sóng.
- Kiểm tra Redis memory.

Hàng tháng:

- Test restore backup trên môi trường không phải production.
- Review secret/access.
- Review dependency CVE.
- Review alert threshold.
