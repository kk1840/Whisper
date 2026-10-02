package com.stepos.whisper.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 PaddleOCR 导出的 inference.yml 的 PostProcess 段。只支持官方导出文件实际用到的写法：
 * 两格缩进的 "key: value" 标量，以及同缩进的 "  - x" 列表项（纯文本或单引号字符串）。
 */
final class PaddleConfig {
    private static final String SECTION = "PostProcess:";
    private static final String DICT_KEY = "character_dict:";

    private final Map<String, String> mScalars = new HashMap<>();
    private final List<String> mCharacterDict = new ArrayList<>();

    PaddleConfig(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        int i = lines.indexOf(SECTION);
        if (i < 0) {
            throw new IllegalStateException(path + " 中没有 " + SECTION);
        }
        String listKey = null;
        for (i++; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("  ")) {
                break;
            }
            if (line.startsWith("  - ")) {
                if (DICT_KEY.equals(listKey)) {
                    mCharacterDict.add(unquote(line.substring(4)));
                }
                continue;
            }
            String entry = line.substring(2);
            int colon = entry.indexOf(": ");
            if (colon > 0) {
                mScalars.put(entry.substring(0, colon), unquote(entry.substring(colon + 2)));
                listKey = null;
            } else {
                listKey = entry.trim();
            }
        }
    }

    float getFloat(String key) {
        return Float.parseFloat(require(key));
    }

    int getInt(String key) {
        return Integer.parseInt(require(key));
    }

    List<String> getCharacterDict() {
        if (mCharacterDict.isEmpty()) {
            throw new IllegalStateException("PostProcess 中没有 " + DICT_KEY);
        }
        return mCharacterDict;
    }

    private String require(String key) {
        String value = mScalars.get(key);
        if (value == null) {
            throw new IllegalStateException("PostProcess 中没有 " + key);
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.startsWith("\"")) {
            throw new IllegalStateException("不支持双引号字符串：" + value);
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}
