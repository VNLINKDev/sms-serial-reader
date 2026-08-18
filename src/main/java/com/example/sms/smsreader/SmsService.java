package com.example.sms.smsreader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.sms.config.AppConfig;
import com.example.sms.exception.ModemTimeoutException;
import com.example.sms.exception.NonOtpSmsException;
import com.example.sms.exception.SerialPortException;
import com.example.sms.serial.AtCommandClient;

/**
 * Service nghiệp vụ đọc SMS từ modem theo chỉ số bộ nhớ và chuyển thành
 * {@link SmsMessage}.
 *
 * Class này là lớp duy nhất biết flow AT command cho SMS:
 * <ol>
 *   Gửi {@code AT+CMGR=index} qua AT client.
 *   Phân tích phản hồi bằng {@link SmsParser}.
 *   Tùy chọn xóa SMS khỏi bộ nhớ modem.
 * </ol>
 *
 * Không xử lý Redis tại đây để giữ boundary rõ ràng: đọc/parse SMS thuộc
 * modem domain, còn delivery sang Redis thuộc integration adapter.
 *
 * Scheduled scan policy:
 * - Đọc toàn bộ SMS trên SIM (AT+CMGL="ALL"), lấy index mới nhất để xử lý.
 * - SMS không khớp OTP pattern (NonOtpSmsException) sẽ bị xóa ngay lập tức
 *   khỏi SIM để giải phóng bộ nhớ, bất kể cấu hình deleteSmsAfterRead.
 */
public class SmsService {

    private static final char SMS_CTRL_Z = 0x1A;
    private static final int SMS_PROMPT_TIMEOUT_MS = 5_000;
    private static final int SMS_SEND_TIMEOUT_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final AtCommandClient atClient;
    private final SmsParser       smsParser;
    private final AppConfig       config;
    private final Pattern         cmglIndexPattern;

    public SmsService(AtCommandClient atClient, SmsParser smsParser, AppConfig config) {
        this.atClient = atClient;
        this.smsParser = smsParser;
        this.config = config;
        this.cmglIndexPattern = Pattern.compile(config.getSmsIndexCmglPattern());
    }

    /**
     * Đọc và phân tích SMS tại chỉ số bộ nhớ modem được truyền vào.
     *
     * Method trả về {@link Optional#empty()} khi lỗi để runtime có thể tiếp tục
     * xử lý các SMS khác. Đây là lựa chọn vận hành: một SMS lỗi không được làm
     * dừng toàn bộ reader process.
     *
     * @param index chỉ số bộ nhớ modem từ thông báo +CMTI.
     * @return {@link Optional} chứa tin nhắn đã phân tích, hoặc rỗng nếu đọc thất bại.
     */
    public Optional<SmsMessage> readAndParse(int index) {
        log.debug("Đọc SMS | index={}", index);
        try {
            String response = atClient.sendAndWait("AT+CMGR=" + index);
            log.trace("SMS raw | index={} | response={}", index, response);
            SmsMessage msg  = smsParser.parse(index, response);
            if (config.isDeleteSmsAfterRead()) {
                deleteSms(index);
            }

            return Optional.of(msg);

        } catch (NonOtpSmsException e) {
            // SMS không phải OTP => xóa ngay khỏi SIM để giải phóng bộ nhớ,
            // bất kể cấu hình DELETE_SMS_AFTER_READ.
            log.info(">>> SMS KHÔNG KHỚP OTP | index={} | content={} <<<",
                    index, nonOtpContent(e));
            deleteSms(index);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Không thể đọc/phân tích SMS tại index {}: {}", index, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Liệt kê tất cả SMS hiện đang lưu trên modem (tiện ích gỡ lỗi).
     */
    public String listAll() {
        return atClient.sendAndWait("AT+CMGL=\"ALL\"");
    }

    /**
     * Đọc và parse tất cả SMS hiện có trên SIM, trả về danh sách đã sắp xếp
     * theo timestamp tăng dần (cũ nhất đầu tiên, mới nhất cuối cùng).
     *
     * <p>KHÔNG dùng «index lớn nhất = tin mới nhất» vì modem tái sử dụng slot
     * đã bị xóa cho SMS đến sau — index thấp có thể là tin mới hơn index cao.
     * Timestamp từ nội dung SMS là nguồn sự thật duy nhất về thứ tự thời gian.</p>
     *
     * <p>Dùng bởi scheduled recovery flow để tránh miss SMS khi event
     * {@code +CMTI} bị mất hoặc process restart.</p>
     *
     * @return danh sách {@link SmsMessage} đã parse, sắp xếp theo timestamp tăng dần.
     *         Rỗng nếu SIM không có SMS hoặc mọi SMS đều parse lỗi.
     */
    public List<SmsMessage> readAndParseAll() {
        List<Integer> indexes = listAllIndexes();
        log.debug("Quét SIM | total={} | indexes={}", indexes.size(), indexes);
        List<SmsMessage> messages = new ArrayList<>();
        for (int index : indexes) {
            try {
                String response = atClient.sendAndWait("AT+CMGR=" + index);
                log.trace("SMS raw | index={} | response={}", index, response);
                SmsMessage msg = smsParser.parse(index, response);
                messages.add(msg);
            } catch (com.example.sms.exception.NonOtpSmsException e) {
                log.info(">>> SMS KHÔNG KHỚP OTP | index={} | content={} <<<",
                        index, nonOtpContent(e));
                deleteSms(index);
            } catch (SerialPortException | ModemTimeoutException e) {
                throw e;
            } catch (Exception e) {
                log.warn("SMS lỗi | index={} | error={}", index, e.getMessage());
            }
        }

        // Sắp xếp theo timestamp thực tế trong nội dung SMS.
        // Modem tái sử dụng slot đã xóa, nên index KHÔNG phản ánh thứ tự nhận.
        messages.sort(Comparator.comparing(msg -> msg.getTimestamp().toInstant()));
        log.debug("Quét xong | otp={}", messages.size());
        return messages;
    }
    
    /**
     * Xóa SMS tại chỉ số được chỉ định khỏi bộ nhớ modem.
     *
     * Xóa là best-effort vì failure ở bước cleanup không nên làm mất kết quả
     * đã parse/publish. Nếu modem từ chối xóa, scheduled unread scan có thể thấy
     * lại SMS này và tầng Redis sẽ chịu trách nhiệm chống ghi đè dữ liệu cũ.
     */
    public void deleteSms(int index) {
        try {
            atClient.sendAndWait("AT+CMGD=" + index);
            log.debug("Đã xóa SMS tại index {}.", index);
        } catch (Exception e) {
            log.warn("Không thể xóa SMS tại index {}: {}", index, e.getMessage());
        }
    }

    /**
     * Lấy toàn bộ danh sách index SMS hiện có trên SIM.
     *
     * Dùng {@code AT+CMGL="ALL"} rồi parse regex lấy tất cả index.
     * Trả về list rỗng nếu SIM trống hoặc lỗi AT command.
     *
     * @return danh sách các index SMS, có thể rỗng.
     */
    public List<Integer> listAllIndexes() {
        String response = atClient.sendAndWait("AT+CMGL=\"ALL\"");
        log.trace("Danh sách SMS raw | response={}", response);

        List<Integer> indexes = new ArrayList<>();
        Matcher matcher = cmglIndexPattern.matcher(response);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }

    /**
     * Chiến lược cleanup SIM theo watermark, dựa trên timestamp thực tế của SMS.
     *
     * <p>Khi số lượng SMS trên SIM vượt ngưỡng {@code simHighWatermark}, xóa
     * các tin nhắn cũ nhất (theo timestamp) và chỉ giữ lại {@code simKeepRecent}
     * tin gần nhất.</p>
     *
     * <p>KHÔNG sort theo index vì modem tái sử dụng slot đã xóa — index cao
     * có thể là tin cũ hơn index thấp. Timestamp từ nội dung SMS là nguồn
     * sự thật duy nhất về thứ tự thời gian.</p>
     *
     * <p>Gọi {@link #readAndParseAll()} để vừa lấy timestamp, vừa tự động
     * xóa các tin non-OTP khỏi SIM trước khi tính watermark.</p>
     *
     * Lý do giữ lại vài tin gần nhất:
     * <ul>
     *   <li>Cho phép truy xuất lại khi gặp lỗi publish Redis.</li>
     *   <li>Hỗ trợ debug khi cần kiểm tra nội dung SMS gốc.</li>
     *   <li>Scheduled scan có thể retry tin mới nhất nếu miss event.</li>
     * </ul>
     *
     * @return số tin nhắn đã xóa thành công.
     */
    public int cleanupOldSms() {
        // readAndParseAll() đã: đọc tất cả SMS, xóa non-OTP, sort theo timestamp tăng dần.
        return cleanupOldSms(readAndParseAll());
    }

    /**
     * Overload nhận list đã đọc sẵn để tránh đọc lại SIM lần thứ hai khi caller
     * đã có list từ {@link #readAndParseAll()} trước đó.
     *
     * @param allOtpSms danh sách OTP SMS đã parse và sort theo timestamp tăng dần.
     * @return số tin nhắn đã xóa thành công.
     */
    public int cleanupOldSms(List<SmsMessage> allOtpSms) {
        int total = allOtpSms.size();

        int highWatermark = config.getSimHighWatermark();
        int keepRecent    = config.getSimKeepRecent();

        if (total < highWatermark) {
            log.debug("SIM có {} SMS OTP — dưới ngưỡng dọn dẹp ({}), không cần dọn dẹp.",
                    total, highWatermark);
            return 0;
        }

        log.info("SIM có {} SMS OTP — vượt ngưỡng dọn dẹp ({}), bắt đầu dọn dẹp (giữ {} SMS gần nhất).",
                total, highWatermark, keepRecent);

        // allOtpSms đã sort timestamp tăng dần: phần tử đầu = cũ nhất, cuối = mới nhất.
        int deleteCount = Math.max(0, total - keepRecent);
        List<SmsMessage> toDelete = allOtpSms.subList(0, deleteCount);
        List<SmsMessage> toKeep   = allOtpSms.subList(deleteCount, total);

        log.info("Kế hoạch dọn dẹp: xóa {} SMS (timestamps: {} → {}), giữ lại {} SMS.",
                toDelete.size(),
                toDelete.isEmpty() ? "-" : toDelete.get(0).getTimestamp(),
                toDelete.isEmpty() ? "-" : toDelete.get(toDelete.size() - 1).getTimestamp(),
                toKeep.size());

        int deleted = 0;
        for (SmsMessage msg : toDelete) {
            try {
                atClient.sendAndWait("AT+CMGD=" + msg.getIndex());
                deleted++;
                log.debug("Dọn dẹp: đã xóa SMS tại index={} timestamp={}.", msg.getIndex(), msg.getTimestamp());
            } catch (Exception e) {
                log.warn("Dọn dẹp: không thể xóa SMS tại index={}: {}", msg.getIndex(), e.getMessage());
            }
        }

        log.info("Dọn dẹp hoàn tất: đã xóa {}/{} SMS OTP, còn lại {} SMS trên SIM.",
                deleted, toDelete.size(), total - deleted);

        return deleted;
    }

    /**
     * Gửi một tin nhắn SMS text tới {@code phoneNumber} với nội dung {@code content}.
     *
     * Flow chuẩn (spec AT+CMGS):
     * 1. AT+CMGF=1     -> chuyển modem sang text mode.
     * 2. AT+CMGS="số"  -> modem trả về dấu nhắc ">" chờ nội dung.
     * 3. Ghi nội dung, kết thúc bằng Ctrl+Z (0x1A) để modem gửi đi.
     *
     * @throws SerialPortException nếu AT+CMGF=1 thất bại hoặc lỗi I/O.
     * @throws ModemTimeoutException nếu không nhận được dấu nhắc ">" hoặc
     *         phản hồi kết thúc đúng hạn.
     */
    public String sendSms(String phoneNumber, String content) {
        return sendSms(phoneNumber, content, SMS_SEND_TIMEOUT_MS);
    }

    public String sendSms(String phoneNumber, String content, int timeoutMs) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Số điện thoại gửi SMS không được để trống.");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Nội dung SMS không được để trống.");
        }

        // Chuyển modem sang chế độ SMS text.
        String modeResult = atClient.sendAndWait("AT+CMGF=1", timeoutMs);
        if (containsError(modeResult) || !modeResult.contains("OK")) {
            throw new SerialPortException(
                    "Không thể chuyển modem sang text mode (AT+CMGF=1). Response: " + modeResult);
        }

        // Bắt đầu phiên gửi và chờ modem cho phép nhập nội dung.
        atClient.drainStale();
        atClient.sendRaw("AT+CMGS=\"" + phoneNumber.trim() + "\"");
        atClient.waitForToken(">", SMS_PROMPT_TIMEOUT_MS, "AT+CMGS chờ dấu nhắc gửi nội dung SMS");

        // Ctrl+Z kết thúc nội dung và yêu cầu modem gửi tin.
        byte[] payload = (content + SMS_CTRL_Z).getBytes(StandardCharsets.US_ASCII);
        atClient.writeRawBytes(payload);

        // Chờ modem trả kết quả cuối cùng.
        String response = atClient.readTerminatedResponse(timeoutMs,
                "AT+CMGS (nội dung SMS tới " + phoneNumber + ")");

        // Thành công phải có message reference (+CMGS), OK và không chứa lỗi.
        if (!response.contains("+CMGS:") || !response.contains("OK") || containsError(response)) {
            throw new SerialPortException("Modem báo lỗi gửi SMS (" + phoneNumber + "): " + response.trim());
        }
        return response;
    }

    private static boolean containsError(String response) {
        return response == null
                || response.contains("ERROR")
                || response.contains("+CME ERROR")
                || response.contains("+CMS ERROR");
    }

    private static String nonOtpContent(NonOtpSmsException exception) {
        String content = exception.getSmsContent();
        if (content == null || content.isBlank()) {
            content = exception.getMessage();
        }
        return content == null ? "" : content.replaceAll("[\\r\\n]+", " | ").trim();
    }
}
