# SMS Serial Reader

A Spring Boot web service that reads incoming SMS messages from a GSM modem
connected via a serial port and publishes them as structured JSON to Redis.

## Features

- Non-blocking serial reading with a bounded receive buffer
- AT command initialisation for GSM modem SMS text mode
- `+CMTI` notification detection for new SMS indexes
- `AT+CMGR` response parsing for transaction ID, OTP, and timestamp
- Redis LIST or Pub/Sub publishing with retry backoff
- Spring Boot dependency injection, configuration, and lifecycle management
- Actuator health/info endpoints
- Structured logging with SLF4J and Logback

## Architecture

```text
SmsReaderApplication          (root)      - Spring Boot entry point
SmsReaderRuntime              (app/)      - runtime lifecycle and polling loop
AppConfig                     (config/)   - Spring configuration properties
SerialPortManager             (serial/)   - open/close serial port
RxBuffer                      (serial/)   - thread-safe bounded receive buffer
SerialReaderService           (serial/)   - dedicated read thread
AtCommandClient               (serial/)   - send AT commands, wait for response
ModemInitializer              (modem/)    - send init sequence
SmsIndexDetector              (modem/)    - detect +CMTI notifications
SmsService                    (sms/)      - AT+CMGR, parse, optional delete
SmsParser                     (sms/)      - parse raw CMGR response
SmsMessage                    (sms/)      - immutable domain model
RedisPublisher                (redis/)    - JSON serialise and push to Redis
```

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Redis | 6+ |
| GSM modem | Connected via USB serial |

## Configuration

Local defaults live in `src/main/resources/application.yml`. The same settings
can be overridden with environment variables:

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERIAL_PORT` | yes | _(none)_ | Serial port name, for example `COM9` or `/dev/ttyUSB0` |
| `BAUD_RATE` | no | `115200` | Modem baud rate |
| `REDIS_HOST` | no | `127.0.0.1` | Redis hostname or IP |
| `REDIS_PORT` | no | `6379` | Redis port |
| `REDIS_PASSWORD` | no | _(empty)_ | Redis AUTH password |
| `REDIS_DATABASE` | no | `0` | Redis database index |
| `REDIS_QUEUE_NAME` | no | `sms:incoming` | List key or Pub/Sub channel |
| `REDIS_MODE` | no | `LIST` | `LIST` or `PUBSUB` |
| `DELETE_SMS_AFTER_READ` | no | `false` | Delete SMS from modem after publish |
| `REDIS_PUBLISH_RETRIES` | no | `3` | Retry attempts for failed publishes |
| `SERVER_PORT` | no | `8080` | Spring Boot HTTP port |

## Running Locally

```bash
mvn clean package
java -jar target/sms-serial-reader-1.0.0.jar
```

Actuator endpoints:

```text
GET /actuator/health
GET /actuator/info
```

## Redis Message Format

Every received SMS is published as JSON:

```json
{
  "index": 12,
  "transactionId": "284939",
  "otp": "668216",
  "timestamp": "2026-04-29T10:04:14+07:00"
}
```

LIST mode pushes with `RPUSH`; PUBSUB mode publishes to the configured channel.

## Tests

```bash
mvn test
```

The test suite should cover parser behavior, buffer/index detection, Spring
configuration binding, and Spring context startup with hardware-facing beans
mocked.
