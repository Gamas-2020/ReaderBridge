package com.feiyu.readerbridge.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * newline-delimited JSON 协议解析。
 *
 * <p>协议要点（与 App 侧 reader-bridge-core 对齐）：</p>
 * <pre>
 * 服务端 → 模组：
 *   {"type":"hello","seq":1,"payload":{"service":"...","version":"..."}}
 *   {"type":"state","seq":2,"payload":{"active":true,"bookTitle":"...","chapterTitle":"...","sentence":"...",
 *                                      "progress":0.0,"paused":true,"voiceId":"...","lastUpdateEpochMs":1763000000000}}
 *   {"type":"event","seq":3,"event":"tts.audio","payload":{...}}
 *   {"type":"error","payload":{"message":"..."}}
 *   {"type":"pong"}
 * </pre>
 *
 * <p><b>防御性要求：</b>任何字段缺失、类型不符、JSON 非法都不能抛异常，更不能弄断连接。
 * 因此本类所有解析方法都对 {@code Throwable} 兜底，坏数据一律当作「无消息 / 空 payload」处理。</p>
 */
public final class Protocol {

    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_STATE = "state";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_PONG = "pong";

    private Protocol() {
    }

    /** 解析后的一条消息。 */
    public static final class Message {
        public final String type;
        public final String eventName;
        public final long seq;
        /** 永不为 null：缺失时为空对象。 */
        public final JsonObject payload;

        Message(String type, String eventName, long seq, JsonObject payload) {
            this.type = type;
            this.eventName = eventName;
            this.seq = seq;
            this.payload = payload;
        }
    }

    /**
     * 解析一行文本。
     *
     * @param line 一行 JSON 文本
     * @return 解析成功的消息；非法输入返回 {@code null}（调用方直接忽略）
     */
    public static Message parseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(trimmed);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String type = optString(obj, "type");
            if (type == null || type.isEmpty()) {
                // 没有 type 的消息无法分类，直接丢弃。
                return null;
            }
            String eventName = optString(obj, "event");
            long seq = optLong(obj, "seq", 0L);
            JsonObject payload = optObject(obj, "payload");
            return new Message(type, eventName, seq, payload);
        } catch (Throwable ignored) {
            // 任何解析异常都视为坏数据，绝不上抛（上抛会打断 WebSocket 接收回调）。
            return null;
        }
    }

    /**
     * 把 {@code type:"state"} 的 payload 转成不可变快照。
     *
     * <p>注意协议里当前句子的字段名是 {@code sentence}（不是 currentSentence）。</p>
     *
     * @param payload state 消息的 payload
     * @return 状态快照；payload 为空时返回 inactive 快照
     */
    public static ReadingStateSnapshot parseState(JsonObject payload) {
        if (payload == null) {
            return ReadingStateSnapshot.INACTIVE;
        }
        try {
            boolean active = optBoolean(payload, "active", false);
            String bookTitle = optString(payload, "bookTitle");
            String chapterTitle = optString(payload, "chapterTitle");
            // 协议字段名是 sentence
            String sentence = optString(payload, "sentence");
            double progress = optDouble(payload, "progress", 0.0D);
            boolean paused = optBoolean(payload, "paused", false);
            String voiceId = optString(payload, "voiceId");
            long lastUpdate = optLong(payload, "lastUpdateEpochMs", System.currentTimeMillis());
            return new ReadingStateSnapshot(active, bookTitle, chapterTitle, sentence,
                    progress, paused, voiceId, lastUpdate);
        } catch (Throwable ignored) {
            return ReadingStateSnapshot.INACTIVE;
        }
    }

    // ------------------------------------------------------------------ 内部：安全取值

    private static JsonObject optObject(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e != null && e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return new JsonObject();
    }

    private static String optString(JsonObject obj, String key) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
                return null;
            }
            JsonPrimitive p = e.getAsJsonPrimitive();
            // 数字 / 布尔也容忍转换为字符串，避免字段类型漂移导致整条消息被丢弃。
            return p.isString() ? p.getAsString() : p.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean optBoolean(JsonObject obj, String key, boolean fallback) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
                return fallback;
            }
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsInt() != 0;
            }
            if (p.isString()) {
                String s = p.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                return "true".equals(s) || "1".equals(s);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return fallback;
    }

    private static double optDouble(JsonObject obj, String key, double fallback) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
                return fallback;
            }
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            if (p.isString()) {
                return Double.parseDouble(p.getAsString().trim());
            }
        } catch (Throwable ignored) {
            // 解析失败（含 NaN 字符串）时使用默认值
        }
        return fallback;
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
                return fallback;
            }
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                return p.getAsLong();
            }
            if (p.isString()) {
                return Long.parseLong(p.getAsString().trim());
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return fallback;
    }
}
