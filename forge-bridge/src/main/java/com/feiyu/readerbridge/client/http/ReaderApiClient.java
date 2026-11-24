package com.feiyu.readerbridge.client.http;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import com.feiyu.readerbridge.impl.MainThreadDispatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Reader App 的本地 REST 客户端。
 *
 * <p>设计要点（踩过的坑，改动前请先读）：</p>
 * <ul>
 *   <li>全部使用 {@link HttpClient#sendAsync}：请求本身跑在 JDK 的内部线程池上，
 *       <b>绝不在渲染线程上做阻塞 IO</b>；</li>
 *   <li>回调统一经 {@link MainThreadDispatcher} 切回 Minecraft 主线程，
 *       因此使用方（GUI / 热键）可以放心直接改界面状态；</li>
 *   <li>所有 POST 都带 {@code Content-Type: application/json} 且必有 body（至少 {@code {}}），
 *       否则 axum 会直接返回 415；</li>
 *   <li>超时 4 秒，失败只打 debug 日志 —— App 没启动是常态，不能因此卡住游戏或刷屏。</li>
 * </ul>
 */
public final class ReaderApiClient {

    // ⚠ 静态常量必须声明在 INSTANCE 之前，不要为了"单例放最上面好看"而挪动。
    // Java 按声明顺序初始化静态字段：INSTANCE 若在前，构造它时实例字段 http
    // 读到的 CONNECT_TIMEOUT 仍是 null，connectTimeout(null) 抛
    // NullPointerException → 包成 ExceptionInInitializerError → 该类永久
    // 不可用（实测表现：首次点开游戏内面板必崩，再点 NoClassDefFoundError）。
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    /** 首次 REST 结果只打一条 INFO，方便联调时在日志里确认通道是否打通（之后全部走 debug）。 */
    private static final AtomicBoolean FIRST_RESULT_LOGGED = new AtomicBoolean(false);

    /** 全局单例。 */
    public static final ReaderApiClient INSTANCE = new ReaderApiClient();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private ReaderApiClient() {
    }

    // ------------------------------------------------------------------ 基础类型

    /** 统一回调：成功给结果，失败给异常（异常也会在主线程上被调用）。 */
    public interface Callback<T> {
        void onSuccess(T value);

        default void onFailure(Throwable error) {
        }
    }

    /** HTTP 非 2xx 时携带状态码，供调用方区分 404（接口还没实现）等情况。 */
    public static final class HttpStatusException extends RuntimeException {
        private final int status;

        public HttpStatusException(int status, String body) {
            super("HTTP " + status + (body == null || body.isBlank() ? "" : (": " + body)));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 书架条目。 */
    public record BookInfo(String id, String title, String author, String sourceId, int totalChapters) {
    }

    /** 章节条目。 */
    public record ChapterInfo(int index, String title, int wordCount) {
    }

    /** 音色条目。 */
    public record VoiceInfo(String id, String name, String provider) {
    }

    /** 搜索结果条目。 */
    public record SearchHit(String sourceId, String bookKey, String title, String author) {
    }

    /** 章节内容摘要（只取面板要显示的部分）。 */
    public record ContentInfo(String bookId, int chapterIndex, String chapterTitle, int sentenceCount) {
    }

    /** 章节正文（正文预览用）：段落列表 + 句子总数。 */
    public record ChapterContent(String bookId, int chapterIndex, String chapterTitle,
                                 List<String> paragraphs, int sentenceCount) {
    }

    // ------------------------------------------------------------------ 书架 / 章节

    /** {@code GET /api/v1/books}。 */
    public void fetchBooks(Callback<List<BookInfo>> callback) {
        getJson("/api/v1/books", root -> {
            List<BookInfo> out = new ArrayList<>();
            JsonArray books = arrayOf(root, "books");
            for (JsonElement element : books) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject book = element.getAsJsonObject();
                out.add(new BookInfo(
                        string(book, "id", ""),
                        string(book, "title", "(无标题)"),
                        string(book, "author", ""),
                        string(book, "sourceId", ""),
                        integer(book, "totalChapters", 0)));
            }
            return out;
        }, callback);
    }

    /** {@code GET /api/v1/books/{bookId}/chapters}。 */
    public void fetchChapters(String bookId, Callback<List<ChapterInfo>> callback) {
        getJson("/api/v1/books/" + pathSegment(bookId) + "/chapters", root -> {
            List<ChapterInfo> out = new ArrayList<>();
            for (JsonElement element : arrayOf(root, "chapters")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject chapter = element.getAsJsonObject();
                out.add(new ChapterInfo(
                        integer(chapter, "index", out.size()),
                        string(chapter, "title", "(无标题)"),
                        integer(chapter, "wordCount", 0)));
            }
            return out;
        }, callback);
    }

    /** {@code POST /api/v1/reader/open}，打开某章（不会自动播放）。 */
    public void openChapter(String bookId, int index, Callback<ContentInfo> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("bookId", bookId);
        body.addProperty("index", index);
        postJson("/api/v1/reader/open", body.toString(), root -> new ContentInfo(
                string(root, "bookId", bookId),
                integer(root, "chapterIndex", index),
                string(root, "chapterTitle", ""),
                integer(root, "sentenceCount", 0)), callback);
    }

    /**
     * {@code GET /api/v1/books/{bookId}/chapters/{index}/content}，取章节正文用于预览。
     *
     * <p>注意该接口返回的字段是 snake_case（{@code book_id} / {@code chapter_title}），
     * 这里优先读驼峰、读不到再读下划线，两种写法都兼容。</p>
     */
    public void fetchChapterContent(String bookId, int index, Callback<ChapterContent> callback) {
        getJson("/api/v1/books/" + pathSegment(bookId) + "/chapters/" + index + "/content", root -> {
            List<String> paragraphs = new ArrayList<>();
            for (JsonElement element : arrayOf(root, "paragraphs")) {
                try {
                    if (element.isJsonPrimitive()) {
                        String text = element.getAsString();
                        if (text != null && !text.isBlank()) {
                            paragraphs.add(text);
                        }
                    }
                } catch (Throwable ignored) {
                    // 段落类型不符时跳过，不影响其它段落。
                }
            }
            int sentenceCount = 0;
            JsonArray sentences = arrayOf(root, "sentences");
            if (sentences.size() > 0) {
                sentenceCount = sentences.size();
            } else {
                sentenceCount = integer(root, "sentenceCount", 0);
            }
            String title = string(root, "chapterTitle", string(root, "chapter_title", ""));
            return new ChapterContent(
                    string(root, "bookId", string(root, "book_id", bookId)),
                    integer(root, "chapterIndex", integer(root, "chapter_index", index)),
                    title,
                    paragraphs,
                    sentenceCount);
        }, callback);
    }

    // ------------------------------------------------------------------ 播放控制    /** {@code POST /api/v1/reader/play|pause|stop|next|prev}。 */
    public void readerAction(String action, Callback<Void> callback) {
        postJson("/api/v1/reader/" + pathSegment(action), "{}", ignored -> null, callback);
    }

    /** {@code POST /api/v1/reader/voice}，body 为 {@code {"voiceId":"..."}}。 */
    public void setVoice(String voiceId, Callback<Void> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("voiceId", voiceId == null ? "" : voiceId);
        postJson("/api/v1/reader/voice", body.toString(), ignored -> null, callback);
    }

    /** 打开某章后立刻开播，等价于 {@code open} + {@code play}。 */
    public void openAndPlay(String bookId, int index, Callback<ContentInfo> callback) {
        openChapter(bookId, index, new Callback<>() {
            @Override
            public void onSuccess(ContentInfo content) {
                readerAction("play", new Callback<>() {
                    @Override
                    public void onSuccess(Void ignored) {
                        callback.onSuccess(content);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        // 打开成功但开播失败：仍然把章节信息交回去，界面能显示"已打开、未播放"。
                        ReaderBridgeMod.LOGGER.debug("[readerbridge] 打开章节成功但开播失败: {}", error.toString());
                        callback.onSuccess(content);
                    }
                });
            }

            @Override
            public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    // ------------------------------------------------------------------ 音色 / 搜索 / 加书架

    /** {@code GET /api/v1/voices}，回调第一个参数是音色列表，第二个是当前音色 id。 */
    public void fetchVoices(Callback<VoicesResult> callback) {
        getJson("/api/v1/voices", root -> {
            List<VoiceInfo> out = new ArrayList<>();
            for (JsonElement element : arrayOf(root, "voices")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject voice = element.getAsJsonObject();
                out.add(new VoiceInfo(
                        string(voice, "id", ""),
                        string(voice, "name", ""),
                        string(voice, "provider", "")));
            }
            return new VoicesResult(out, string(root, "current", ""));
        }, callback);
    }

    /** 音色查询结果。 */
    public record VoicesResult(List<VoiceInfo> voices, String current) {
    }

    /**
     * {@code POST /api/v1/search}。
     *
     * <p>App 侧该路由可能还没上线（返回 404）——调用方应把 {@link HttpStatusException#status()}
     * 为 404 当作「搜索不可用」处理，隐藏搜索框而不是报错。</p>
     */
    public void search(String sourceId, String keyword, Callback<List<SearchHit>> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("sourceId", sourceId);
        body.addProperty("keyword", keyword);
        postJson("/api/v1/search", body.toString(), root -> {
            List<SearchHit> out = new ArrayList<>();
            JsonArray results = arrayOf(root, "results");
            for (JsonElement element : results) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject hit = element.getAsJsonObject();
                out.add(new SearchHit(
                        string(hit, "sourceId", sourceId),
                        string(hit, "bookKey", string(hit, "id", "")),
                        string(hit, "title", "(无标题)"),
                        string(hit, "author", "")));
            }
            return out;
        }, callback);
    }

    /** {@code POST /api/v1/books}，把搜索结果加进书架。 */
    public void addBook(String sourceId, String bookKey, Callback<Void> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("sourceId", sourceId);
        body.addProperty("bookKey", bookKey);
        postJson("/api/v1/books", body.toString(), ignored -> null, callback);
    }

    // ------------------------------------------------------------------ 内部实现

    private interface Parser<T> {
        T parse(JsonElement root);
    }

    private <T> void getJson(String path, Parser<T> parser, Callback<T> callback) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (Throwable t) {
            deliverFailure(callback, t);
            return;
        }
        send(request, path, parser, callback);
    }

    private <T> void postJson(String path, String jsonBody, Parser<T> parser, Callback<T> callback) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
        } catch (Throwable t) {
            deliverFailure(callback, t);
            return;
        }
        send(request, path, parser, callback);
    }

    private <T> void send(HttpRequest request, String path, Parser<T> parser, Callback<T> callback) {
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        ReaderBridgeMod.LOGGER.debug("[readerbridge] 请求 {} 失败: {}", path, cause.toString());
                        if (FIRST_RESULT_LOGGED.compareAndSet(false, true)) {
                            ReaderBridgeMod.LOGGER.info("[readerbridge] 首次 REST 调用失败（App 未启动？）: {} -> {}",
                                    path, cause.toString());
                        }
                        deliverFailure(callback, cause);
                        return;
                    }
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        HttpStatusException failure =
                                new HttpStatusException(status, trim(response.body()));
                        ReaderBridgeMod.LOGGER.debug("[readerbridge] 请求 {} 返回 {}", path, status);
                        if (FIRST_RESULT_LOGGED.compareAndSet(false, true)) {
                            ReaderBridgeMod.LOGGER.info("[readerbridge] 首次 REST 调用返回非 2xx: {} -> HTTP {}",
                                    path, status);
                        }
                        deliverFailure(callback, failure);
                        return;
                    }
                    T value;
                    try {
                        JsonElement root = JsonParser.parseString(blankToEmpty(response.body()));
                        value = parser.parse(root);
                    } catch (Throwable t) {
                        ReaderBridgeMod.LOGGER.debug("[readerbridge] 解析 {} 响应失败: {}", path, t.toString());
                        deliverFailure(callback, t);
                        return;
                    }
                    if (FIRST_RESULT_LOGGED.compareAndSet(false, true)) {
                        ReaderBridgeMod.LOGGER.info("[readerbridge] 首次 REST 调用成功: {} -> {} 字节",
                                path, blankToEmpty(response.body()).length());
                    }
                    // 关键：结果统一回主线程，界面与其它监听器都在主线程上安全处理。
                    MainThreadDispatcher.dispatch(() -> {
                        try {
                            callback.onSuccess(value);
                        } catch (Throwable t) {
                            ReaderBridgeMod.LOGGER.error("[readerbridge] 处理 {} 结果时异常", path, t);
                        }
                    });
                });
    }

    private <T> void deliverFailure(Callback<T> callback, Throwable error) {
        MainThreadDispatcher.dispatch(() -> {
            try {
                callback.onFailure(error);
            } catch (Throwable t) {
                ReaderBridgeMod.LOGGER.error("[readerbridge] 处理请求失败回调时异常", t);
            }
        });
    }

    /** 当前 App 的 HTTP 基地址（与 WS 端口保持一致，配置改端口后自动跟随）。 */
    public static String baseUrl() {
        return "http://127.0.0.1:" + ReaderBridgeConfig.wsPort();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    private static String blankToEmpty(String body) {
        return body == null || body.isBlank() ? "{}" : body;
    }

    /** 把 bookId / action 放进 URL 路径：保留冒号等合法字符，只转义空白与百分号。 */
    private static String pathSegment(String raw) {
        if (raw == null) {
            return "";
        }
        String encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
        // URLEncoder 会把 ':' 编成 %3A，axum 虽然能解码，但保持原样更利于日志比对。
        return encoded.replace("%3A", ":").replace("%3a", ":");
    }

    /**
     * 兼容三种响应外形，统一剥到真正的载荷对象上：
     * <ul>
     *   <li>{@code {"ok":true,"data":{...}}} —— 统一包装（App 新契约）</li>
     *   <li>{@code {"state":{...}}} —— 单对象查询的旧外形</li>
     *   <li>裸对象 —— 各端点原先直接返回字段（已实测的 books/chapters/content/voices）</li>
     * </ul>
     * 解析器只面对真实载荷，因此两种外形都能读。
     */
    private static JsonObject unwrapObject(JsonElement root) {
        try {
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonElement ok = obj.get("ok");
            if (ok != null && ok.isJsonPrimitive() && ok.getAsJsonPrimitive().isBoolean()) {
                JsonElement data = obj.get("data");
                if (data != null && data.isJsonObject()) {
                    return data.getAsJsonObject();
                }
            }
            // {"state":{...}}：仅在没有同层业务字段时下钻，避免误伤自身带 state 字段的载荷。
            JsonElement state = obj.get("state");
            if (state != null && state.isJsonObject()
                    && !obj.has("books") && !obj.has("chapters") && !obj.has("voices")) {
                return state.getAsJsonObject();
            }
            return obj;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonArray arrayOf(JsonElement root, String field) {
        try {
            JsonObject obj = unwrapObject(root);
            if (obj != null) {
                JsonElement value = obj.get(field);
                if (value != null && value.isJsonArray()) {
                    return value.getAsJsonArray();
                }
            }
        } catch (Throwable ignored) {
            // 防御：字段缺失/类型不符时退化为空数组。
        }
        return new JsonArray();
    }

    private static String string(JsonElement root, String key, String fallback) {
        try {
            JsonObject obj = unwrapObject(root);
            if (obj == null) {
                return fallback;
            }
            JsonElement value = obj.get(key);
            if (value == null || value.isJsonNull()) {
                return fallback;
            }
            String text = value.getAsString();
            return text == null || text.isEmpty() ? fallback : text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int integer(JsonElement root, String key, int fallback) {
        try {
            JsonObject obj = unwrapObject(root);
            if (obj == null) {
                return fallback;
            }
            JsonElement value = obj.get(key);
            if (value == null || value.isJsonNull()) {
                return fallback;
            }
            return value.getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 供外部（例如调试命令）复用解析的小工具。 */
    public static Function<String, JsonElement> jsonParser() {
        return JsonParser::parseString;
    }
}
