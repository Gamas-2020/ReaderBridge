package com.feiyu.readerbridge.client.fq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 什锦铺（fqapp）本机数据源客户端。
 *
 * <p>设计原因：模组不再依赖任何自建的外部进程，直接读用户自己的什锦铺。
 * 什锦铺把番茄内容暴露成 loopback REST（默认 127.0.0.1:18921），
 * 我们只做只读消费，不写它的状态、不改它的数据。
 *
 * <p>已实测的三条路径（2026-11-24 逐条验过）：
 * <ul>
 *   <li>搜索 {@code GET /api/v1/search?q=<关键词>} —— 参数名只能是 q 或 query，
 *       其它写法一律 400；返回体是 {@code search_tabs[].data[].book_data[]}。</li>
 *   <li>目录 {@code GET /api/v1/books/{book_id}/directory} —— 章节列表在
 *       {@code data.item_data_list[]}，每项有 item_id / title。</li>
 *   <li>正文 {@code GET /api/v1/chapters/{item_id}} —— <b>吃的是章节 item_id，不是 book_id</b>，
 *       传 book_id 会返回 code 101004「该书不存在或已停止合作」。正文在 {@code data.content}，
 *       <b>有两种格式</b>，由 {@code data.text_type} 区分：</li>
 *   <li>{@code text_type=1}（或任何含标签的内容）—— {@code <header/><article><p idx="0">…</p>…</article>}
 *       形式的 HTML；</li>
 *   <li>{@code text_type=2} —— <b>纯文本</b>，内容里一个 HTML 标签都没有，段落用 {@code \n} 分隔，
 *       <b>第一行是章节标题</b>（实测：book_id 7276384138653862966《我不是戏神》第 1 章，
 *       content 2133 字、{@code <p} 计数 0、{@code \n} 计数 68、{@code paragraphs_num=68}）。
 *       早期只按 {@code <p>} 解析，这类书解析出 0 段，面板上正文整页空白。</li>
 *   <li>注意 {@code crypt_status=2} 的书内容<b>仍是明文</b>（实测读到正常中文），
 *       不要因为 {@code crypt_status != 0} 就跳过 —— 这里没有任何 crypt 判定。</li>
 * </ul>
 *
 * <p>线程模型：所有方法都是阻塞 IO，<b>禁止在渲染线程或主线程调用</b>；
 * 调用方应放在自己的后台线程里（面板里是 {@code NodeScheduler} 的单线程池）。
 */
public final class FqApiClient {

    /** 什锦铺默认监听端口。用户可在模组配置里改。 */
    public static final int DEFAULT_PORT = 18921;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int SEARCH_LIMIT = 40;

    private final String base;
    private final HttpClient http;

    public FqApiClient() {
        this(DEFAULT_PORT);
    }

    public FqApiClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 什锦铺是纯本机服务，绝不走系统代理，否则用户开了代理就连不上自己。
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return base;
    }

    // ---------------------------------------------------------------- 数据模型

    /**
     * 什锦铺（fqapp）的源标识。
     *
     * <p>多书源改造后 {@link BookInfo#sourceId} 用它区分「本地什锦铺」与配置里声明的
     * 公网规则书源；旧存档/旧调用方没有这个字段时一律按它处理。
     */
    public static final String SOURCE_FQAPI = "fqapi";

    /** 搜索结果里的一本书。 */
    public static final class BookInfo {
        public final String bookId;
        public final String name;
        public final String author;
        public final String intro;
        /**
         * 来源书源 id：{@link #SOURCE_FQAPI} 表示什锦铺；
         * 其它值是 {@code config/readerbridge-common.toml} 里 {@code extraSources} 中规则的 {@code id}。
         * 永不返回 null/空串（缺省归一成 {@link #SOURCE_FQAPI}）。
         */
        public final String sourceId;

        /** 兼容旧调用方：等价于 {@code sourceId = "fqapi"}。 */
        public BookInfo(String bookId, String name, String author, String intro) {
            this(bookId, name, author, intro, SOURCE_FQAPI);
        }

        /**
         * @param sourceId 来源书源 id；null 或空白归一成 {@link #SOURCE_FQAPI}
         */
        public BookInfo(String bookId, String name, String author, String intro, String sourceId) {
            this.bookId = bookId == null ? "" : bookId;
            this.name = name == null ? "" : name;
            this.author = author == null ? "" : author;
            this.intro = intro == null ? "" : intro;
            String sid = sourceId == null ? "" : sourceId.trim();
            this.sourceId = sid.isEmpty() ? SOURCE_FQAPI : sid;
        }

        @Override
        public String toString() {
            String base = name.isEmpty() ? bookId : name;
            // 什锦铺保持原样显示（老玩家已经习惯），额外书源带上源名便于区分同名书
            return SOURCE_FQAPI.equals(sourceId) ? base : base + " · " + sourceId;
        }
    }

    /** 目录里的一章。 */
    public static final class ChapterInfo {
        public final int index;
        public final String itemId;
        public final String title;

        public ChapterInfo(int index, String itemId, String title) {
            this.index = index;
            this.itemId = itemId == null ? "" : itemId;
            this.title = title == null || title.isEmpty() ? ("第 " + (index + 1) + " 章") : title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // ---------------------------------------------------------------- 三个只读接口

    /**
     * 按关键词搜书。关键词为空直接返回空列表（该接口不支持空串列全量）。
     *
     * <p>异常策略：网络不通抛 IOException，由调用方提示「什锦铺没开」；
     * JSON 结构异常抛 IOException 而不是静默返回空，避免面板显示「搜不到」误导用户。
     */
    public List<BookInfo> search(String keyword) throws IOException {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String url = base + "/api/v1/search?q="
                + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8);
        JsonObject root = getJson(url);

        List<BookInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JsonArray tabs = arr(root, "search_tabs");
        for (JsonElement tabEl : tabs) {
            if (!tabEl.isJsonObject()) {
                continue;
            }
            JsonArray data = arr(tabEl.getAsJsonObject(), "data");
            for (JsonElement dataEl : data) {
                if (!dataEl.isJsonObject()) {
                    continue;
                }
                JsonArray books = arr(dataEl.getAsJsonObject(), "book_data");
                for (JsonElement bookEl : books) {
                    if (!bookEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject b = bookEl.getAsJsonObject();
                    String id = str(b, "book_id");
                    if (id.isEmpty() || !seen.add(id)) {
                        continue;
                    }
                    out.add(new BookInfo(
                            id,
                            str(b, "book_name"),
                            str(b, "author"),
                            firstNonEmpty(str(b, "abstract"), str(b, "intro"))));
                    if (out.size() >= SEARCH_LIMIT) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** 取目录。走目录接口而不是书详情，因为只需 item_id + 标题。 */
    public List<ChapterInfo> chapters(String bookId) throws IOException {
        if (bookId == null || bookId.isEmpty()) {
            return Collections.emptyList();
        }
        String url = base + "/api/v1/books/" + enc(bookId) + "/directory";
        JsonObject root = getJson(url);
        JsonObject data = obj(root, "data");
        JsonArray items = arr(data, "item_data_list");

        List<ChapterInfo> out = new ArrayList<>(items.size());
        int idx = 0;
        for (JsonElement el : items) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject it = el.getAsJsonObject();
            String itemId = str(it, "item_id");
            if (itemId.isEmpty()) {
                continue;
            }
            out.add(new ChapterInfo(idx++, itemId, str(it, "title")));
        }
        return out;
    }

    /**
     * 取正文段落。
     *
     * <p>接口返回两种格式（见类注释），这里按 {@code data.text_type} 分流：
     * 有 {@code <article}/{@code <p} 标签的走 HTML 扫描；否则按换行切纯文本。
     * 两条路径都会剥实体（纯文本里也可能有 {@code &amp;} 这类残留）并折叠空白
     * （含全角空格 {@code \u3000} 与 NBSP）。
     *
     * <p><b>兜底</b>：两条路径都解析出 0 段而 {@code content} 非空时，再按换行切一次、
     * 且<b>不丢首段</b>——宁可多一段（可能把标题念进去）也不要整页空白。
     * 这条兜底一旦触发会往 stderr 打一行，便于现场定位格式又变了。
     *
     * <p>纯文本分支会尝试丢掉「第一行 = 章节标题」那一行，判定见
     * {@link #isChapterHeadingLine(String, String)}（保守三条件，避免误删真正文）。
     *
     * @param itemId 章节 item_id（不是 book_id）
     */
    public List<String> paragraphs(String itemId) throws IOException {
        if (itemId == null || itemId.isEmpty()) {
            return Collections.emptyList();
        }
        String url = base + "/api/v1/chapters/" + enc(itemId);
        JsonObject root = getJson(url);
        JsonElement codeEl = root.get("code");
        int code = codeEl != null && codeEl.isJsonPrimitive() ? codeEl.getAsInt() : 0;
        if (code != 0) {
            throw new IOException("章节读取失败（code=" + code + "）：" + str(root, "message"));
        }
        JsonObject data = obj(root, "data");
        String content = str(data, "content");
        if (content.isEmpty()) {
            System.err.println("[readerbridge] 正文为空：item_id=" + itemId
                    + " text_type=" + intOf(data, "text_type", -1)
                    + " paragraphs_num=" + intOf(data, "paragraphs_num", -1));
            return Collections.emptyList();
        }
        final String title = str(data, "title");
        final int textType = intOf(data, "text_type", -1);

        List<String> out;
        if (looksLikeHtml(content)) {
            out = htmlToParagraphs(content);
        } else {
            out = plainTextToParagraphs(content, title);
        }
        if (out.isEmpty()) {
            // 兜底：格式又不是上面两种（或标签结构变了），按换行切且不丢首段。
            // 这里刻意保留首行：未知格式下无法判断它是不是标题，空白比多一句更糟。
            out = linesToParagraphs(content, false, title);
            System.err.println("[readerbridge] 正文格式未识别，已按换行兜底：item_id=" + itemId
                    + " text_type=" + textType + " len=" + content.length()
                    + " paragraphs_num=" + intOf(data, "paragraphs_num", -1)
                    + " -> " + out.size() + " 段");
        }
        return out;
    }

    // ---------------------------------------------------------------- 正文格式判定

    /** 内容里是否有 HTML 段落标签（{@code text_type=1} 的形态）。 */
    static boolean looksLikeHtml(String content) {
        if (content == null) {
            return false;
        }
        // 只认 <p / <article 这两个正文容器标签：这是 text_type=1 的唯一实测形态。
        // 不把 <div/<br 之类也算进来 —— 纯文本正文里偶尔出现尖括号字面量时，
        // 误判成 HTML 会让 HTML 分支解析出 0 段（虽然还有兜底，但会多打一条误导性日志）。
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<p") || lower.contains("<article");
    }

    /**
     * 纯文本正文 → 段落。
     *
     * <p>{@code text_type=2} 的正文第一行是章节标题（实测《我不是戏神》第 1 章首行
     * 「第1章 戏鬼回家」，与 {@code data.title} 完全相同）。标题混进正文会让 TTS
     * 每章开头都念一遍标题，所以丢掉；判定本身要保守。
     */
    static List<String> plainTextToParagraphs(String content, String title) {
        return linesToParagraphs(content, true, title);
    }

    /**
     * 按换行切段（兼容 {@code \r\n} / {@code \r}），逐行 trim 并丢空行。
     *
     * @param dropHeadingLine {@code true} 时，若首行被判定为章节标题则丢掉它
     */
    static List<String> linesToParagraphs(String content, boolean dropHeadingLine, String title) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return out;
        }
        String[] lines = content.split("\r\n|\r|\n");
        int start = 0;
        if (dropHeadingLine && lines.length > 1) {
            String first = collapseWs(unescape(lines[0]));
            if (isChapterHeadingLine(first, title)) {
                start = 1;
            }
        }
        for (int i = start; i < lines.length; i++) {
            // 纯文本里也做实体反解：实测有些书的正文残留 &nbsp; / &amp;，
            // 复用 HTML 分支同一套 unescape，不另写一份。
            String text = collapseWs(unescape(lines[i]));
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    /**
     * 首行是否为章节标题。
     *
     * <p><b>为什么这么保守</b>：{@code text_type=2} 的书首行确实是标题，但并非所有书都如此
     * （有的书首段直接就是正文）。误删一格真正文比多念一个标题更严重，所以只在下面
     * 三种明确信号下才认定为标题：
     * <ol>
     *   <li>首行与 {@code data.title} 完全相同（最常见）；</li>
     *   <li>首行以 {@code data.title} 结尾（有的书首行是「书名 第3章 xxx」）；</li>
     *   <li>首行以「第」开头、紧跟数字（阿拉伯或中文数字，允许空格）、再紧跟
     *       章 / 节 / 回 / 卷 / 篇 —— 也就是章节序号本身。</li>
     * </ol>
     * 其它情况（包括首行是「第一章的标题」这种不含序号的写法）一律保留。
     */
    static boolean isChapterHeadingLine(String first, String title) {
        if (first == null || first.isEmpty()) {
            return false;
        }
        String heading = first.trim();
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty() && (heading.equals(t) || heading.endsWith(t))) {
            return true;
        }
        if (!heading.startsWith("第")) {
            return false;
        }
        int i = 1;
        boolean sawNumber = false;
        while (i < heading.length()) {
            char c = heading.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u3000') {
                i++;
                continue;
            }
            if ((c >= '0' && c <= '9') || isChineseNumeral(c)) {
                sawNumber = true;
                i++;
                continue;
            }
            break;
        }
        if (!sawNumber || i >= heading.length()) {
            return false;
        }
        char unit = heading.charAt(i);
        return unit == '章' || unit == '节' || unit == '回' || unit == '卷' || unit == '篇';
    }

    /** 中文数字（含「零〇两」这类常见写法）。 */
    private static boolean isChineseNumeral(char c) {
        return "零〇一二三四五六七八九十百千万两".indexOf(c) >= 0;
    }

    /** 读一个 int 字段，缺失/非数字时返回 {@code fallback}。 */
    private static int intOf(JsonObject parent, String key, int fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement el = parent.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- HTTP

    private JsonObject getJson(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断：" + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + "：" + url);
        }
        String body = resp.body();
        if (body == null || body.isEmpty()) {
            throw new IOException("空响应：" + url);
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IOException("响应不是合法 JSON：" + url, e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- JSON 取值（容错）

    private static JsonObject obj(JsonObject parent, String key) {
        if (parent == null) {
            return new JsonObject();
        }
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arr(JsonObject parent, String key) {
        if (parent == null) {
            return new JsonArray();
        }
        JsonElement el = parent.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject parent, String key) {
        if (parent == null) {
            return "";
        }
        JsonElement el = parent.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return "";
        }
        String s = el.getAsString();
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    // ---------------------------------------------------------------- HTML → 段落

    /**
     * 把 {@code <article><p idx="0">…</p>…} 拆成段落列表。
     *
     * <p>只认 {@code <p} 开标签；{@code <pre>} 之类不当作段落（正文里没出现过，
     * 但保留判断以免把代码块内容拼进正文）。
     */
    static List<String> htmlToParagraphs(String html) {
        List<String> out = new ArrayList<>();
        int i = 0;
        final int n = html.length();
        while (i < n) {
            int lt = html.indexOf("<p", i);
            if (lt < 0) {
                break;
            }
            // 必须是 <p 或 <p> 或 <p ...>，排除 <pre 这类同前缀标签
            char after = lt + 2 < n ? html.charAt(lt + 2) : '>';
            if (after != '>' && after != ' ' && after != '\t' && after != '\n' && after != '\r') {
                i = lt + 2;
                continue;
            }
            int gt = html.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            if (html.charAt(lt + 1) == 'r') { // <pre
                i = gt + 1;
                continue;
            }
            int close = html.indexOf("</p", gt);
            if (close < 0) {
                break;
            }
            String inner = html.substring(gt + 1, close);
            // 顺序很关键：先剥标签再还原实体。
            // 反过来（旧的 unescape→stripTags）会把正文里合法的 &lt;xxx&gt; 先还原成 <xxx>
            // 再当标签吞掉 —— 「他&lt;神&gt;了」这种正文会少字，TTS 直接念缺。
            String text = collapseWs(unescape(stripTags(inner)));
            if (!text.isEmpty()) {
                out.add(text);
            }
            int nextLt = html.indexOf('>', close);
            i = nextLt < 0 ? n : nextLt + 1;
        }
        return out;
    }

    private static String stripTags(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = s.substring(i + 1, semi);
            String rep = entity(ent);
            if (rep == null) {
                sb.append(c);
                i++;
            } else {
                sb.append(rep);
                i = semi + 1;
            }
        }
        return sb.toString();
    }

    private static String entity(String ent) {
        switch (ent) {
            case "nbsp": return " ";
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "ldquo": return "“";
            case "rdquo": return "”";
            case "hellip": return "…";
            case "mdash": return "—";
            default: break;
        }
        if (ent.length() > 1 && ent.charAt(0) == '#') {
            try {
                int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                if (cp > 0 && cp <= 0x10FFFF) {
                    return new String(Character.toChars(cp));
                }
            } catch (RuntimeException ignored) {
                // 非法实体原样保留
            }
        }
        return null;
    }

    /** 折叠空白：连续空白并成一个空格，去全角空格与 NBSP。 */
    static String collapseWs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean space = c == ' ' || c == '\t' || c == '\n' || c == '\r'
                    || c == '\u3000' || c == '\u00A0';
            if (space) {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().trim();
    }
}
