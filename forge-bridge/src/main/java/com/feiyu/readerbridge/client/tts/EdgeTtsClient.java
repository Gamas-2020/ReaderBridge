package com.feiyu.readerbridge.client.tts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微软 Edge「朗读」在线语音合成客户端，只用 JDK 17 内置 API（{@link HttpClient} 的 WebSocket）。
 *
 * <p>一次 {@link #synthesize(List, String, int)} 走完整流程：
 * 生成 Sec-MS-GEC v2 令牌 → WSS 握手 → 发 {@code Path:speech.config} →
 * 发 {@code Path:ssml} → 收文本帧（{@code Path:audio.metadata} / {@code Path:turn.end}）
 * 与二进制帧（2 字节大端头长度 + 头文本 + mp3 音频）→ 用 mp3 帧头解析总时长 →
 * 把 {@code WordBoundary} 聚合回每句的起止毫秒。
 *
 * <p>协议细节与 {@code reader-app/core/src/tts/edge.rs} 逐项对齐，改动前请先读那份 Rust 实现。
 *
 * <h2>线程</h2>
 * 本类是同步阻塞的：在调用线程上等待整块合成结束（总超时 90 秒），不要在主线程每帧调用。
 */
public final class EdgeTtsClient {

    // ---- 协议常量（都是官方客户端里写死的公开值，不是机密）

    /** WSS 端点（令牌放查询参数，放 header 会 403）。 */
    private static final String WSS_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";

    /** 端点版本串，与令牌一起放查询参数。 */
    private static final String SEC_MS_GEC_VERSION = "2-143.0.3650.75";

    /** Edge 扩展的 Origin：服务端据此判断请求来自官方客户端。 */
    private static final String EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    /** 官方客户端 UA；服务端不严格校验，但缺了会显得可疑。 */
    private static final String EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    /** 只要 mp3：服务端对四种 riff-* PCM 变体都返回 0 字节。 */
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /** speech.config 的 metadataoptions：整块合成只需要词级边界。 */
    private static final String METADATA_OPTIONS =
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}";

    /** {@code Offset} / {@code Duration} 的单位是 100ns。 */
    private static final long HNS_PER_MS = 10_000L;

    /** 词边界归属允许向后看几句（服务端会改写数字/多音字，跨几句找才自愈）。 */
    private static final int LOOKAHEAD_SENTENCES = 4;

    /** 整块合成总超时（毫秒）。 */
    private static final long TOTAL_TIMEOUT_MS = 90_000L;

    /** 握手超时（毫秒）。 */
    private static final long HANDSHAKE_TIMEOUT_MS = 20_000L;

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /** 进程内复用的 HttpClient（内部线程是 daemon，不会阻止 JVM 退出）。 */
    private static volatile HttpClient http;

    private EdgeTtsClient() {
    }

    // ---- 对外数据结构

    /** 一句话在音频时间轴上的起止位置。 */
    public static final class Span {
        /** 对应 {@code sentences} 的下标。 */
        public final int index;
        /** 起始毫秒（含）。 */
        public final long startMs;
        /** 结束毫秒（不含）。 */
        public final long endMs;

        public Span(int index, long startMs, long endMs) {
            this.index = index;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        @Override
        public String toString() {
            return "Span{" + index + ": " + startMs + ".." + endMs + "ms}";
        }
    }

    /** 一次整块合成的结果。 */
    public static final class Result {
        /** 完整 mp3 字节（24kHz / 48kbps / mono）。 */
        public final byte[] mp3;
        /** mp3 总时长（毫秒），由 MPEG 帧头累加得出。 */
        public final long durationMs;
        /** 与 {@code sentences} 等长、按下标一一对应。 */
        public final List<Span> spans;
        /** 实际使用的完整音色名。 */
        public final String voiceName;

        public Result(byte[] mp3, long durationMs, List<Span> spans, String voiceName) {
            this.mp3 = mp3;
            this.durationMs = durationMs;
            this.spans = spans;
            this.voiceName = voiceName;
        }
    }

    /** 服务端下发的一个词边界。 */
    private static final class WordBoundary {
        final long offsetHns;
        final long durationHns;
        final String text;

        WordBoundary(long offsetHns, long durationHns, String text) {
            this.offsetHns = offsetHns;
            this.durationHns = durationHns;
            this.text = text;
        }
    }

    // ---- 主入口

    /**
     * 把若干句中文（或任意文本）合成成一段 mp3，并给出每句的时间轴。
     *
     * @param sentences      待合成句子（一个自然段切成句），按顺序
     * @param shortVoiceName 短音色名，如 {@code "zh-CN-XiaoxiaoNeural"}
     * @param rate           语速档位 -10..10（0 表示正常；映射到 SSML 的 +N%）
     * @return 合成结果；失败抛 {@link RuntimeException}（带清晰原因）
     */
    public static Result synthesize(List<String> sentences, String shortVoiceName, int rate) {
        if (sentences == null || sentences.isEmpty()) {
            throw new RuntimeException("Edge TTS: sentences 不能为空");
        }
        String voice = shortVoiceName == null ? "" : shortVoiceName.trim();
        if (voice.isEmpty()) {
            throw new RuntimeException("Edge TTS: shortVoiceName 不能为空");
        }

        String fullVoiceName = voiceFullName(voice);
        String plainText = joinSentences(sentences);
        if (plainText.trim().isEmpty()) {
            throw new RuntimeException("Edge TTS: 待合成文本全是空白");
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS);
        RemoteSynth remote = synthesizeRemote(plainText, fullVoiceName, rate, deadlineNanos);

        EdgeMp3.Info info;
        try {
            info = EdgeMp3.probe(remote.audio);
        } catch (RuntimeException e) {
            throw new RuntimeException("Edge TTS: mp3 帧头解析失败（收到的不是有效 mp3）: " + e.getMessage(), e);
        }
        long durationMs = info.durationMs;

        List<Span> spans = aggregateSpans(sentences, remote.words, durationMs);
        return new Result(remote.audio, durationMs, Collections.unmodifiableList(spans), fullVoiceName);
    }

    /** 合成产物。 */
    private static final class RemoteSynth {
        final byte[] audio;
        final List<WordBoundary> words;

        RemoteSynth(byte[] audio, List<WordBoundary> words) {
            this.audio = audio;
            this.words = words;
        }
    }

    // ---- 连接 → 两帧 → 收帧

    private static RemoteSynth synthesizeRemote(String text, String fullVoiceName, int rate, long deadlineNanos) {
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String gec = EdgeMsGec.generateNow();
        String url = WSS_ENDPOINT
                + "?TrustedClientToken=" + EdgeMsGec.TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + gec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        FrameCollector collector = new FrameCollector();
        WebSocket ws;
        try {
            ws = client().newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS))
                    .header("Origin", EDGE_ORIGIN)
                    .header("User-Agent", EDGE_UA)
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=" + randomMuid() + ";")
                    .buildAsync(URI.create(url), collector)
                    .get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Edge TTS: WebSocket 握手超时（" + HANDSHAKE_TIMEOUT_MS + "ms）", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Edge TTS: WSS 握手被拒 —— " + describeHandshakeFailure(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Edge TTS: 握手被中断", e);
        }

        try {
            // 1) speech.config：告诉服务端要 mp3 + 词级边界
            String timestamp = rfc1123Now();
            String configJson = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                    + METADATA_OPTIONS + ",\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
            String configMsg = "X-Timestamp:" + timestamp
                    + "\r\nContent-Type:application/json; charset=utf-8"
                    + "\r\nPath:speech.config\r\n\r\n" + configJson;
            ws.sendText(configMsg, true).get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);

            // 2) ssml：音色必须是完整名，短名会在 6 秒后被服务端 RST
            int ratePct = Math.max(-10, Math.min(10, rate)) * 10;
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + xmlEscape(fullVoiceName) + "'>"
                    + "<prosody pitch='+0Hz' rate='" + String.format(Locale.ROOT, "%+d%%", ratePct) + "' volume='+0%'>"
                    + xmlEscape(text)
                    + "</prosody></voice></speak>";
            String requestId = UUID.randomUUID().toString().replace("-", "");
            String ssmlMsg = "X-RequestId:" + requestId
                    + "\r\nContent-Type:application/ssml+xml"
                    + "\r\nX-Timestamp:" + timestamp + "Z"
                    + "\r\nPath:ssml\r\n\r\n" + ssml;
            ws.sendText(ssmlMsg, true).get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);

            // 3) 等 turn.end（或连接关闭 / 出错）
            try {
                collector.done.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException("Edge TTS: 合成超时（90 秒），已收到 "
                        + collector.audioSize() + " 字节音频", e);
            } catch (ExecutionException e) {
                if (collector.audioSize() == 0) {
                    throw new RuntimeException("Edge TTS: 接收失败 —— " + e.getCause(), e.getCause());
                }
                // 已经拿到音频就继续，收尾错误不丢整段。
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Edge TTS: 合成被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Edge TTS: 发送失败 —— " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Edge TTS: 发送超时", e);
        } finally {
            try {
                ws.abort();
            } catch (RuntimeException ignored) {
                // abort 失败无关紧要
            }
        }

        byte[] audio = collector.audioBytes();
        if (audio.length == 0) {
            StringBuilder reason = new StringBuilder("Edge TTS: 服务端没有返回音频");
            if (collector.closeReason != null) {
                reason.append("（连接被关闭：").append(collector.closeReason).append("）");
            }
            if (collector.lastError != null) {
                reason.append("，错误：").append(collector.lastError);
            }
            throw new RuntimeException(reason.toString());
        }
        return new RemoteSynth(audio, collector.wordsSnapshot());
    }

    private static HttpClient client() {
        HttpClient local = http;
        if (local == null) {
            synchronized (EdgeTtsClient.class) {
                local = http;
                if (local == null) {
                    local = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    http = local;
                }
            }
        }
        return local;
    }

    private static long remainingMillis(long deadlineNanos) {
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(1L, left);
    }

    private static String describeHandshakeFailure(Throwable cause) {
        if (cause instanceof WebSocketHandshakeException) {
            WebSocketHandshakeException handshake = (WebSocketHandshakeException) cause;
            String body = "";
            try {
                Object payload = handshake.getResponse().body();
                body = payload instanceof String ? (String) payload : String.valueOf(payload);
            } catch (RuntimeException ignored) {
                // 响应体可能是二进制或超大，取不到就别取
            }
            if (body != null && body.length() > 300) {
                body = body.substring(0, 300);
            }
            return "HTTP " + handshake.getResponse().statusCode()
                    + "（Origin / Sec-MS-GEC / Cookie 校验失败或端点变更）" + (body == null ? "" : " " + body);
        }
        return String.valueOf(cause);
    }

    // ---- 帧收集

    /**
     * 接收器：文本帧按 {@code Path:*} 分流，二进制帧按 {@code [2 字节大端头长度][头][音频]} 拆音频。
     */
    private static final class FrameCollector implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final List<WordBoundary> words = new ArrayList<>();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private volatile String lastError;
        private volatile String closeReason;

        int audioSize() {
            synchronized (audio) {
                return audio.size();
            }
        }

        byte[] audioBytes() {
            synchronized (audio) {
                return audio.toByteArray();
            }
        }

        List<WordBoundary> wordsSnapshot() {
            synchronized (words) {
                return new ArrayList<>(words);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String frame = textBuffer.toString();
                textBuffer.setLength(0);
                handleTextFrame(frame);
            }
            if (!finished.get()) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.write(chunk, 0, chunk.length);
            if (last) {
                byte[] frame = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                handleBinaryFrame(frame);
            }
            if (!finished.get()) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason = "code=" + statusCode + " reason=" + reason;
            finish(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finish(error);
        }

        private void finish(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (error == null) {
                done.complete(null);
            } else {
                done.completeExceptionally(error);
            }
        }

        private void handleTextFrame(String frame) {
            if (frame.contains("Path:turn.end")) {
                finish(null);
                return;
            }
            if (frame.contains("Path:audio.metadata")) {
                String body = frameBody(frame);
                if (body != null) {
                    try {
                        List<WordBoundary> parsed = parseMetadataWords(body);
                        synchronized (words) {
                            words.addAll(parsed);
                        }
                    } catch (RuntimeException e) {
                        // 解析失败不致命：音频照出，只是这句拿不到精确边界。
                        lastError = "metadata 解析失败: " + e.getMessage();
                    }
                }
                return;
            }
            String lower = frame.toLowerCase(Locale.ROOT);
            if (lower.contains("\"error\"") || lower.contains("error:")) {
                lastError = frame.length() > 400 ? frame.substring(0, 400) : frame;
            }
        }

        private void handleBinaryFrame(byte[] frame) {
            if (frame.length < 2) {
                return;
            }
            int headerLength = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
            int start = 2 + headerLength;
            if (frame.length > start) {
                synchronized (audio) {
                    audio.write(frame, start, frame.length - start);
                }
            }
        }
    }

    /** 取文本帧的 body：头与 body 之间隔着空行（个别环境只有 {@code \n\n}）。 */
    private static String frameBody(String frame) {
        int index = frame.indexOf("\r\n\r\n");
        if (index >= 0) {
            return frame.substring(index + 4);
        }
        index = frame.indexOf("\n\n");
        if (index >= 0) {
            return frame.substring(index + 2);
        }
        return null;
    }

    // ---- metadata 解析

    /** 解析一帧 {@code Path:audio.metadata} 的 body，只保留 {@code WordBoundary} 条目。 */
    private static List<WordBoundary> parseMetadataWords(String body) {
        List<WordBoundary> out = new ArrayList<>();
        Object root = EdgeJson.parse(body);
        Map<String, Object> frame = EdgeJson.asObject(root);
        if (frame == null) {
            return out;
        }
        for (Object itemValue : EdgeJson.asArray(frame.get("Metadata"))) {
            Map<String, Object> item = EdgeJson.asObject(itemValue);
            if (item == null) {
                continue;
            }
            if (!"WordBoundary".equalsIgnoreCase(EdgeJson.asString(item.get("Type"), ""))) {
                continue;
            }
            Map<String, Object> data = EdgeJson.asObject(item.get("Data"));
            if (data == null) {
                continue;
            }
            long offset = EdgeJson.asLong(data.get("Offset"), 0L);
            long duration = EdgeJson.asLong(data.get("Duration"), 0L);
            Map<String, Object> text = EdgeJson.asObject(data.get("text"));
            String wordText = text == null ? "" : EdgeJson.asString(text.get("Text"), "");
            out.add(new WordBoundary(offset, duration, wordText));
        }
        return out;
    }

    // ---- 句子时间轴聚合

    /**
     * 把词边界聚合回「每句的起止毫秒」。
     *
     * <p>用游标法：在**当前句未消费部分**找得到该词 → 归当前句并推进游标；找不到 →
     * 往后看 {@link #LOOKAHEAD_SENTENCES} 句，命中就切过去；仍找不到 → 归当前句但不推进游标
     * （宁可落在当前句也不丢条目）。
     *
     * <p>输出长度恒等于 {@code sentences.size()}：整句一个词都没匹配到的句子用上一句的
     * 结束时间占位（首句用 0），并保证 {@code 0 <= startMs <= endMs <= durationMs} 且单调不减。
     */
    private static List<Span> aggregateSpans(List<String> sentences, List<WordBoundary> words, long durationMs) {
        int count = sentences.size();
        char[][] chars = new char[count][];
        for (int i = 0; i < count; i++) {
            String sentence = sentences.get(i);
            chars[i] = sentence == null ? new char[0] : sentence.toCharArray();
        }

        int[] cursors = new int[count];
        long[] firstMs = new long[count];
        long[] lastMs = new long[count];
        boolean[] hasFirst = new boolean[count];
        boolean[] hasLast = new boolean[count];

        int current = 0;
        for (WordBoundary word : words) {
            char[] needle = stripWhitespace(word.text);
            if (needle.length == 0 || count == 0) {
                continue;
            }
            int advance = findWord(chars[current], cursors[current], needle);
            if (advance < 0) {
                int limit = Math.min(current + LOOKAHEAD_SENTENCES, count);
                for (int k = current + 1; k < limit; k++) {
                    int pos = findWord(chars[k], 0, needle);
                    if (pos >= 0) {
                        current = k;
                        advance = pos;
                        break;
                    }
                }
            }
            long start = word.offsetHns / HNS_PER_MS;
            long end = (word.offsetHns + word.durationHns) / HNS_PER_MS;
            if (!hasFirst[current]) {
                hasFirst[current] = true;
                firstMs[current] = start;
            }
            hasLast[current] = true;
            lastMs[current] = end;
            if (advance >= 0) {
                cursors[current] = advance;
            }
        }

        List<Span> spans = new ArrayList<>(count);
        long previousEnd = 0L;
        for (int i = 0; i < count; i++) {
            long start;
            long end;
            if (hasFirst[i] && hasLast[i]) {
                start = Math.max(firstMs[i], previousEnd);
                end = Math.max(lastMs[i], start);
            } else {
                start = previousEnd;
                end = previousEnd;
            }
            previousEnd = end;
            // clamp 到 [0, durationMs]
            long max = Math.max(0L, durationMs);
            start = Math.max(0L, Math.min(start, max));
            end = Math.max(start, Math.min(end, max));
            spans.add(new Span(i, start, end));
        }
        return spans;
    }

    /** 去掉文本里的空白字符（服务端的词边界不含空白，本地句子里可能有缩进换行）。 */
    private static char[] stripWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return new char[0];
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        char[] out = new char[sb.length()];
        sb.getChars(0, sb.length(), out, 0);
        return out;
    }

    /**
     * 在 {@code chars[from..]} 里忽略空白地查找 {@code needle}（调用前已去空白）。
     *
     * @return 命中后**匹配结束位置**的游标（供下次从该位置继续找），没找到返回 -1
     */
    private static int findWord(char[] chars, int from, char[] needle) {
        if (needle.length == 0 || from >= chars.length) {
            return -1;
        }
        for (int start = from; start < chars.length; start++) {
            int j = start;
            int k = 0;
            while (k < needle.length) {
                while (j < chars.length && Character.isWhitespace(chars[j])) {
                    j++;
                }
                if (j >= chars.length || chars[j] != needle[k]) {
                    break;
                }
                j++;
                k++;
            }
            if (k == needle.length) {
                return j;
            }
        }
        return -1;
    }

    // ---- 文本工具

    /** 多句拼成一段：服务端按整块合成，句界靠空格，词边界靠 metadata。 */
    private static String joinSentences(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence == null || sentence.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sentence);
        }
        return sb.toString();
    }

    /**
     * 短音色名 → 服务端要求的完整音色名。
     *
     * <p>{@code zh-CN-XiaoxiaoNeural} →
     * {@code Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)}。
     * 短名会让服务端在 6 秒后 RST。
     */
    static String voiceFullName(String shortName) {
        String voice = shortName == null ? "" : shortName.trim();
        if (voice.isEmpty()) {
            return voice;
        }
        if (voice.regionMatches(true, 0, "Microsoft ", 0, 10)) {
            return voice;
        }
        String[] parts = voice.split("-");
        if (parts.length < 3) {
            return voice;
        }
        String locale = parts[0] + "-" + parts[1];
        StringBuilder name = new StringBuilder(parts[2]);
        for (int i = 3; i < parts.length; i++) {
            name.append('-').append(parts[i]);
        }
        return "Microsoft Server Speech Text to Speech Voice (" + locale + ", " + name + ")";
    }

    /** XML 转义（SSML 正文与属性都要过一遍）。 */
    static String xmlEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String rfc1123Now() {
        return RFC1123.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    /** 32 位大写十六进制 MUID（官方客户端每次随机生成，服务端会校验格式）。 */
    private static String randomMuid() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    // ---- 冒烟测试入口

    /**
     * 真连一次线上服务合成的自测入口（不参与运行期逻辑，可随时删）。
     *
     * <pre>
     * java -cp build\classes\java\main com.feiyu.readerbridge.client.tts.EdgeTtsClient [输出路径]
     * </pre>
     *
     * @param args 可选：首个参数是 mp3 输出路径，默认 {@code E:\rb_build\edgejava.mp3}
     */
    public static void main(String[] args) {
        List<String> sentences = new ArrayList<>();
        sentences.add("白日依山尽，黄河入海流。");
        sentences.add("欲穷千里目，更上一层楼。");

        Path out = Path.of(args.length > 0 ? args[0] : "E:\\rb_build\\edgejava.mp3");
        long startNanos = System.nanoTime();
        Result result = synthesize(sentences, "zh-CN-XiaoxiaoNeural", 0);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, result.mp3);
        } catch (IOException e) {
            throw new RuntimeException("冒烟测试写文件失败: " + out, e);
        }

        System.out.println("elapsedMs=" + elapsedMs);
        System.out.println("voiceName=" + result.voiceName);
        System.out.println("mp3Bytes=" + result.mp3.length);
        System.out.println("durationMs=" + result.durationMs);
        System.out.println("spans=" + result.spans);
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(8, result.mp3.length); i++) {
            head.append(String.format(Locale.ROOT, "%02X ", result.mp3[i] & 0xFF));
        }
        System.out.println("head8=" + head.toString().trim());
        System.out.println("output=" + out.toAbsolutePath());
        System.out.flush();
        // HttpClient 的 selector 线程是 daemon，这里显式退出只为让冒烟脚本拿到确定退出码。
        System.exit(0);
    }
}
