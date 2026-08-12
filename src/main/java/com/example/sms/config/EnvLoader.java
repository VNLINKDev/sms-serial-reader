package com.example.sms.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Đọc cấu hình từ:
 * 1. Environment Variables
 * 2. File .env cùng thư mục với file jar
 */
public final class EnvLoader {

    private static final Map<String, String> ENV = new HashMap<>();

    static {
        String envFile = System.getProperty("env.file", ".env");
        Path path = Path.of(envFile);

        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);

                for (String line : lines) {
                    line = line.trim();

                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    int index = line.indexOf('=');
                    if (index <= 0) {
                        continue;
                    }

                    String key = line.substring(0, index).trim();
                    String value = line.substring(index + 1).trim();

                    ENV.put(key, value);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private EnvLoader() {
    }

    public static String get(String key) {
        // Ưu tiên Environment Variables
        String value = System.getenv(key);

        if (value != null && !value.isBlank()) {
            value = value.trim();
        } else {
            // Sau đó mới đọc từ file .env
            value = ENV.get(key);
        }

        if (value != null) {
            value = value.trim();

            // File .env thường đặt regex/chuỗi trong dấu nháy. Dấu nháy chỉ dùng
            // để bao giá trị, không phải một phần của giá trị cấu hình.
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    value = value.substring(1, value.length() - 1);
                }
            }

            // Unescape double backslashes (\\) to single backslashes (\)
            value = value.replace("\\\\", "\\");
        }

        return value;
    }

}
