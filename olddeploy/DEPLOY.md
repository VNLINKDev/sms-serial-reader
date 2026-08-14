# SMS Serial Reader — Hướng dẫn Deploy lên Linux Server

## Yêu cầu hệ thống

| Thành phần | Phiên bản tối thiểu |
|---|---|
| OS | Ubuntu 20.04+ / Debian 11+ / CentOS 8+ |
| Java | JDK / JRE 11 |
| RAM | 256 MB (khuyến nghị 512 MB) |
| Thiết bị | Modem GSM kết nối qua cổng USB (`/dev/ttyUSB*`) |

---

## Bước 1 — Cài đặt Java 11 trên server

```bash
sudo apt update
sudo apt install -y openjdk-11-jre-headless

# Kiểm tra
java -version
# Kết quả mong đợi: openjdk version "11.x.x"
```

---

## Bước 2 — Tạo thư mục ứng dụng

```bash
sudo mkdir -p /opt/sms-reader
sudo chown $USER:$USER /opt/sms-reader
```

---

## Bước 3 — Copy file lên server

Từ máy Windows của bạn, chạy lệnh sau trong PowerShell/CMD:

```powershell
# Thay your_user và your_server_ip bằng thông tin thực
$SERVER = "your_user@your_server_ip"
$APP_DIR = "/opt/sms-reader"

# Copy JAR (đặt tên thành app.jar)
scp "target\sms-serial-reader-1.0.0.jar" "${SERVER}:${APP_DIR}/app.jar"

# Copy scripts và env
scp start.sh stop.sh restart.sh .env "${SERVER}:${APP_DIR}/"
```

Hoặc dùng **WinSCP** / **FileZilla** để kéo thả các file:
- `target/sms-serial-reader-1.0.0.jar` → đổi tên thành `app.jar`
- `start.sh`
- `stop.sh`
- `restart.sh`
- `.env`

---

## Bước 4 — Chuẩn bị file trên server

SSH vào server, sau đó:

```bash
cd /opt/sms-reader

# Fix line endings (bắt buộc vì file tạo trên Windows)
sudo apt install -y dos2unix
dos2unix .env start.sh stop.sh restart.sh

# Cấp quyền thực thi scripts
chmod +x start.sh stop.sh restart.sh

# Tạo thư mục logs
mkdir -p logs
```

---

## Bước 5 — Kiểm tra & cấu hình `.env`

```bash
nano /opt/sms-reader/.env
```

Các giá trị **bắt buộc phải đúng**:

```bash
# ✅ Profile phải là prod
SPRING_PROFILES_ACTIVE=prod

# ✅ Kiểm tra đúng port của modem
SERIAL_DEVICE=/dev/ttyUSB1
SERIAL_PORT=/dev/ttyUSB1

# ✅ Điền database nếu đang trống
REDIS_DATABASE=0

# ✅ Thông tin Redis server thực tế
REDIS_HOST=103.21.149.190
REDIS_PORT=6868
REDIS_PASSWORD=your_password
```

### Tìm đúng tên port modem GSM:

```bash
# Xem các thiết bị USB tty
ls /dev/ttyUSB*
# hoặc
dmesg | grep -i tty | tail -20
```

---

## Bước 6 — Cấp quyền truy cập serial port

```bash
# Thêm user hiện tại vào group dialout
sudo usermod -aG dialout $USER

# Apply ngay mà không cần logout
newgrp dialout

# Kiểm tra quyền
ls -la /dev/ttyUSB1
# Kết quả mong đợi: crw-rw---- 1 root dialout ...
```

---

## Bước 7 — Khởi động ứng dụng

```bash
cd /opt/sms-reader

# Khởi động
./start.sh

# Xem log realtime
tail -f logs/app.log
```

---

## Quản lý ứng dụng

### Dừng

```bash
cd /opt/sms-reader
./stop.sh
```

### Khởi động lại

```bash
cd /opt/sms-reader
./restart.sh
```

### Kiểm tra trạng thái

```bash
# Health check
curl http://localhost:8080/actuator/health

# Kết quả khi OK:
# {"status":"UP","components":{"serial":{"status":"UP",...},"redis":{"status":"UP"}}}

# Xem process đang chạy
cat /opt/sms-reader/app.pid
ps aux | grep app.jar

# Xem log
tail -f /opt/sms-reader/logs/app.log
```

---

## Bước 8 (Tuỳ chọn) — Tự động khởi động với systemd

Để app tự restart khi server reboot:

```bash
sudo nano /etc/systemd/system/sms-reader.service
```

Nội dung file:

```ini
[Unit]
Description=SMS Serial Reader
After=network.target

[Service]
Type=forking
User=your_linux_user
WorkingDirectory=/opt/sms-reader
PIDFile=/opt/sms-reader/app.pid
ExecStart=/opt/sms-reader/start.sh
ExecStop=/opt/sms-reader/stop.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
# Thay your_linux_user bằng user của bạn
sudo sed -i "s/your_linux_user/$USER/" /etc/systemd/system/sms-reader.service

# Enable và start
sudo systemctl daemon-reload
sudo systemctl enable sms-reader
sudo systemctl start sms-reader

# Kiểm tra
sudo systemctl status sms-reader

# Xem log qua journalctl
sudo journalctl -u sms-reader -f
```

---

## Cấu trúc thư mục trên server

```
/opt/sms-reader/
├── app.jar           ← Fat JAR đã build
├── .env              ← Biến môi trường (KHÔNG commit lên git)
├── start.sh          ← Khởi động app
├── stop.sh           ← Dừng app
├── restart.sh        ← Khởi động lại app
├── app.pid           ← PID của process (tự tạo khi chạy)
└── logs/
    └── app.log       ← Log file (tự tạo khi chạy)
```

---

## Xử lý lỗi thường gặp

### ❌ `Permission denied` khi đọc serial port

```bash
sudo usermod -aG dialout $USER && newgrp dialout
```

### ❌ App khởi động nhưng Redis lỗi

```bash
# Kiểm tra kết nối Redis
redis-cli -h 103.21.149.190 -p 6868 -a your_password ping
# Kết quả mong đợi: PONG
```

### ❌ `java: command not found`

```bash
sudo apt install -y openjdk-11-jre-headless
export PATH=$PATH:/usr/lib/jvm/java-11-openjdk-amd64/bin
```

### ❌ App tự dừng sau vài giây

```bash
# Xem 100 dòng log cuối để tìm nguyên nhân
tail -100 /opt/sms-reader/logs/app.log
```

### ❌ Port 8080 bị chiếm

```bash
sudo lsof -i :8080
# Đổi port trong .env: SERVER_PORT=9090
```

---

## Cập nhật phiên bản mới

```bash
# Trên server
cd /opt/sms-reader

# Dừng app hiện tại
./stop.sh

# Copy JAR mới lên (từ máy local)
# scp target/sms-serial-reader-1.0.0.jar user@server:/opt/sms-reader/app.jar

# Khởi động lại
./start.sh
```
