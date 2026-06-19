package com.example.sms.serial;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Service đọc byte thô từ serial port trên một daemon thread riêng và nạp vào
 * {@link RxBuffer}.
 *
 * Lớp này là boundary I/O thấp nhất của ứng dụng: không parse modem command,
 * không hiểu SMS, không publish Redis. Tách trách nhiệm như vậy giúp giữ read
 * loop đơn giản và giảm nguy cơ block serial reader bởi business logic.
 *
 * <p><b>Reconnect mechanism:</b> vòng lặp đọc gồm hai cấp:
 * <ul>
 *   <li>Outer loop: quản lý reconnect — lấy InputStream mới sau mỗi lần port lỗi,
 *       với exponential backoff tăng dần theo số lần thất bại liên tiếp.</li>
 *   <li>Inner loop: đọc byte thực sự. Khi gặp lỗi I/O (không phải timeout bình thường),
 *       thoát vào outer loop để reconnect.</li>
 * </ul>
 * Thiết kế đảm bảo reader thread không chết vĩnh viễn khi USB modem bị ngắt
 * tạm thời; sau khi modem cắm lại, ứng dụng tự phục hồi mà không cần restart.
 *
 * <p>{@code running} là volatile để shutdown từ thread khác được nhìn thấy ngay
 * mà không cần synchronized block.
 */
@RequiredArgsConstructor
public class SerialReaderService {

    private static final Logger log = LoggerFactory.getLogger(SerialReaderService.class);

    /**
     * Exponential backoff khi reconnect: 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
     */
    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_MAX_MS  = 30_000;

    private final SerialPortManager portManager;
    private final RxBuffer          rxBuffer;

    private volatile boolean running      = false;

    /**
     * True khi thread đang trong quá trình chờ/thực hiện reconnect.
     * Được đọc bởi {@link com.example.sms.health.SerialPortHealthIndicator}
     * để phân biệt trạng thái RECONNECTING với DOWN hoàn toàn.
     */
    private volatile boolean reconnecting = false;

    private Thread readerThread;

    // -------------------------------------------------------------------------
    // Vòng đời
    // -------------------------------------------------------------------------

    /**
     * Khởi động reader thread nền.
     *
     * Thread là daemon để JVM không bị giữ lại nếu Spring context shutdown
     * trong tình huống lỗi startup. Lifecycle bình thường vẫn gọi {@link #stop()}.
     */
    public void start() {
        running      = true;
        readerThread = new Thread(this::readLoop, "sms-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("Serial reader thread started.");
    }

    /**
     * Báo dừng reader thread và đánh thức các command đang wait trên buffer.
     *
     * Interrupt giúp thoát khỏi blocking read nếu driver hỗ trợ; wakeAll giúp
     * các thread chờ response không nằm im đến hết timeout trong quá trình shutdown.
     */
    public void stop() {
        running = false;
        rxBuffer.wakeAll();

        if (readerThread != null) {
            readerThread.interrupt();
        }

        log.info("Serial reader thread stopped.");
    }

    /** True nếu reader đang chạy (bao gồm cả khi đang reconnect). */
    public boolean isRunning() {
        return running;
    }

    /** True nếu đang trong quá trình reconnect (port chưa sẵn sàng). */
    public boolean isReconnecting() {
        return reconnecting;
    }

    // -------------------------------------------------------------------------
    // Vòng lặp đọc hai cấp
    // -------------------------------------------------------------------------

    private void readLoop() {
        int reconnectAttempts = 0;
        final byte[] buf = new byte[4096];

        log.debug("Read loop started for port '{}'.", portManager.getPortName());

        while (running) {

            // ── Outer loop: lấy InputStream (reconnect nếu port đang đóng) ──────
            InputStream is;
            try {
                is               = portManager.getInputStream();
                reconnecting     = false;
                reconnectAttempts = 0;
                log.debug("Serial port '{}' active, reading...", portManager.getPortName());

            } catch (Exception e) {
                if (!running) break;
                // Port chưa mở được (ví dụ: vừa reconnect chưa xong).
                reconnectAttempts++;
                doReconnect(reconnectAttempts, e.getMessage());
                continue;
            }

            // ── Inner loop: đọc byte từ port đang mở ────────────────────────────
            while (running) {
                try {
                    int n = is.read(buf);
                    if (n <= 0) continue;

                    String data = new String(buf, 0, n, StandardCharsets.US_ASCII);
                    rxBuffer.append(data);

                } catch (Exception e) {
                    if (!running) return;
                    if (isReadTimeout(e)) continue;  // timeout bán-blocking bình thường

                    // Lỗi I/O thực sự (port ngắt, driver crash) → trigger reconnect.
                    log.error("Serial read error on '{}': {}. Will attempt reconnect.",
                            portManager.getPortName(), e.getMessage(), e);
                    break; // thoát inner loop → outer loop xử lý reconnect
                }
            }

            // ── Sau inner loop: reconnect nếu vẫn còn running ───────────────────
            if (!running) break;

            reconnectAttempts++;
            doReconnect(reconnectAttempts, "read loop terminated unexpectedly");
        }

        reconnecting = false;
        log.debug("Read loop exiting.");
    }

    /**
     * Chờ theo exponential backoff rồi thử gọi {@link SerialPortManager#reconnect()}.
     *
     * <ul>
     *   <li>Nếu reconnect thành công: outer loop lấy InputStream mới ở vòng tiếp theo.</li>
     *   <li>Nếu thất bại: outer loop tiếp tục thử — không bỏ cuộc.</li>
     * </ul>
     *
     * @param attempt số lần thất bại liên tiếp (1-based), dùng để tính backoff
     * @param reason  lý do trigger reconnect, chỉ dùng để log
     */
    private void doReconnect(int attempt, String reason) {
        reconnecting = true;

        // cap bit-shift ở 5 để tránh overflow: 2^5 = 32 → 32s → RECONNECT_MAX_MS clamp
        long delay = Math.min(
                RECONNECT_BASE_MS * (1L << Math.min(attempt - 1, 5)),
                RECONNECT_MAX_MS);

        log.warn("Reconnect attempt {} (reason: '{}') — waiting {}ms before retry.",
                attempt, reason, delay);

        if (!sleepMs(delay)) return; // interrupt = đang shutdown, bỏ qua

        if (!running) return;

        try {
            portManager.reconnect();
            // Đánh thức AT command thread đang wait để chúng timeout sớm
            // thay vì treo đến hết timeout trong lúc buffer đã stale.
            rxBuffer.wakeAll();
            log.info("Serial port '{}' reconnected successfully on attempt {}.",
                    portManager.getPortName(), attempt);
        } catch (Exception e) {
            log.error("Reconnect attempt {} failed (will retry next cycle): {}",
                    attempt, e.getMessage());
        }
    }

    private static boolean isReadTimeout(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout");
    }

    /** Sleep an toàn. Trả về false nếu bị interrupt (đang shutdown). */
    private static boolean sleepMs(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
