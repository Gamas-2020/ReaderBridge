package com.feiyu.readerbridge.client.tts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简**只读** JSON 解析器（零依赖，只用 JDK 标准库）。
 *
 * <p>为什么手写：Edge TTS 只需要解析 `Path:audio.metadata` 帧里那一小段 JSON，
 * 为它引第三方库（Gson/Jackson）会让这个包不再能脱离 Minecraft/Forge 的 classpath
 * 独立运行（冒烟测试要求 {@code java -cp build\classes\java\main} 就能跑）。
 *
 * <p>能力边界（刻意保守，不追求完整 JSON 规范）：
 * <ul>
 *   <li>支持对象 / 数组 / 字符串（含 \\uXXXX 与常见转义）/ 整数 / 浮点 / true / false / null</li>
 *   <li>整数一律解析成 {@link Long}，带小数点或指数的解析成 {@link Double}</li>
 *   <li>不支持尾随逗号、注释、单引号字符串（服务端不会发这些）</li>
 * </ul>
 *
 * <p>线程安全性：解析器实例持有可变游标，只做单次解析；{@link #parse} 是纯函数。
 */
final class EdgeJson {

    private final String src;
    private int pos;

    private EdgeJson(String src) {
        this.src = src;
    }

    /**
     * 解析一段 JSON 文本。
     *
     * @param text 原始 JSON
     * @return {@link Map} / {@link List} / {@link String} / {@link Long} / {@link Double} / {@link Boolean} / {@code null}
     * @throws IllegalArgumentException JSON 结构非法
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 文本为 null");
        }
        EdgeJson parser = new EdgeJson(text);
        parser.skipWs();
        Object value = parser.readValue();
        parser.skipWs();
        if (parser.pos < parser.src.length()) {
            throw parser.error("解析结束后仍有尾随字符");
        }
        return value;
    }

    /** 把值当对象读；不是对象时返回 null。 */
    static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) raw;
            return typed;
        }
        return null;
    }

    /** 把值当数组读；不是数组时返回空列表。 */
    static List<Object> asArray(Object value) {
        if (value instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> typed = (List<Object>) raw;
            return typed;
        }
        return List.of();
    }

    /** 读字符串字段；缺字段或类型不符时返回 fallback。 */
    static String asString(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    /** 读整数字段；缺字段或类型不符时返回 fallback。 */
    static long asLong(Object value, long fallback) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            return (long) (double) d;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ---- 解析实现

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("期望一个值，但已到文本末尾");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // 吃掉 '{'
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw error("对象键必须是字符串");
            }
            String key = readString();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw error("对象键后缺少 ':'");
            }
            pos++;
            skipWs();
            out.put(key, readValue());
            skipWs();
            if (pos >= src.length()) {
                throw error("对象未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw error("对象里期望 ',' 或 '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        pos++; // 吃掉 '['
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            out.add(readValue());
            skipWs();
            if (pos >= src.length()) {
                throw error("数组未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw error("数组里期望 ',' 或 ']'");
        }
    }

    private String readString() {
        pos++; // 吃掉起始引号
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("转义符后没有内容");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append(readHex4());
                default -> throw error("不支持的转义 \\" + esc);
            }
        }
    }

    private char readHex4() {
        if (pos + 4 > src.length()) {
            throw error("\\u 转义不足 4 位");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(src.charAt(pos + i), 16);
            if (digit < 0) {
                throw error("\\u 转义含非十六进制字符");
            }
            value = (value << 4) | digit;
        }
        pos += 4;
        return (char) value;
    }

    private Object readNumber() {
        int start = pos;
        boolean floating = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '-' || c == '+') {
                pos++;
            } else if (c == '.') {
                floating = true;
                pos++;
            } else if (c == 'e' || c == 'E') {
                floating = true;
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("非法字符 '" + src.charAt(pos) + "'");
        }
        String raw = src.substring(start, pos);
        try {
            if (floating) {
                return Double.valueOf(raw);
            }
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException ignore) {
                throw error("非法数字 " + raw);
            }
        }
    }

    private void expectWord(String word) {
        if (!src.startsWith(word, pos)) {
            throw error("期望字面量 " + word);
        }
        pos += word.length();
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        int from = Math.max(0, pos - 20);
        int to = Math.min(src.length(), pos + 20);
        return new IllegalArgumentException("JSON 解析失败（位置 " + pos + "）：" + message
                + "，上下文: " + src.substring(from, to));
    }
}
