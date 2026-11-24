package com.feiyu.readerbridge.client.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一份「规则书源」的 JSON 描述。
 *
 * <p>字段与 Rust 侧 {@code reader-app/core/src/source/rulejson.rs} 的 {@code SourceRule}
 * 一一对应（沿用 camelCase 键名），因此同一份 .json 在 App 与模组里都能直接用：
 *
 * <pre>{@code
 * {
 *   "id": "quanben",
 *   "name": "全本小说网",
 *   "baseUrl": "https://www.quanben.io",
 *   "timeoutMs": 20000,
 *   "headers": { "User-Agent": "..." },
 *   "search":   { "url": "/s?q={keyword}", "list": "div.list h3 a", "title": "text",
 *                 "key": "attr:href", "filterKeyword": false, "pages": 1,
 *                 "skipHrefPrefix": ["/", "http"] },
 *   "chapters": { "url": "/amp{bookPath}list.html", "list": "ul.list3 li a",
 *                 "title": "text", "key": "attr:href" },
 *   "content":  { "url": "{chapterKey}", "list": "div.articlebody p:not(:first-child)" }
 * }
 * }</pre>
 *
 * <p>为什么自己手写解析而不是让 Gson 反射映射：书源是<b>用户手写的</b>文件，
 * 出错必须回一句能看懂的话（「chapters.list 为空」），而不是 Gson 的
 * {@code Expected BEGIN_OBJECT but was STRING at line 1 column 37}。另外还要兼容
 * Rust 侧两种历史写法（{@code filter_keyword} / {@code filterKeyword}、
 * {@code skip_href_prefix} / {@code skipHrefPrefix}）。
 *
 * <p>不可变对象：解析成功后所有字段 final，可在线程间共享。
 */
public final class SourceRule {

    /** 单次请求默认超时（毫秒），与 Rust 侧一致。 */
    public static final int DEFAULT_TIMEOUT_MS = 15_000;
    /** 超时下限：再小基本必然失败，反而让用户以为书源坏了。 */
    public static final int MIN_TIMEOUT_MS = 2_000;
    /** 超时上限：避免一条配置把搜索卡住几分钟。 */
    public static final int MAX_TIMEOUT_MS = 60_000;

    /** 书源唯一标识，写进 {@link com.feiyu.readerbridge.client.fq.FqApiClient.BookInfo#sourceId}。 */
    public final String id;
    /** 展示名（搜索结果里跟书名一起显示源）。 */
    public final String name;
    /** 书源维护者/站点域名，仅展示。 */
    public final String author;
    /** 站点根地址，用于把条目里的相对链接补成绝对 URL。 */
    public final String baseUrl;
    /** 请求头（User-Agent / Referer / Accept-Language…）。 */
    public final Map<String, String> headers;
    /** 单次请求超时（毫秒），已 clamp 到 {@value #MIN_TIMEOUT_MS}~{@value #MAX_TIMEOUT_MS}。 */
    public final int timeoutMs;
    /** 搜索规则（必填）。 */
    public final ListRule search;
    /** 目录规则（必填）。 */
    public final ListRule chapters;
    /** 正文规则（必填）。 */
    public final ContentRule content;

    private SourceRule(String id, String name, String author, String baseUrl,
                       Map<String, String> headers, int timeoutMs,
                       ListRule search, ListRule chapters, ContentRule content) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.timeoutMs = timeoutMs;
        this.search = search;
        this.chapters = chapters;
        this.content = content;
    }

    // ---------------------------------------------------------------- 子规则

    /**
     * 「列表 → 条目」规则（搜索页与目录页共用一套结构）。
     *
     * <p>取值表达式（{@link #title} / {@link #authorExpr} / {@link #key} 都支持）：
     * <ul>
     *   <li>{@code text} —— 元素自身文本（折叠空白）</li>
     *   <li>{@code html} —— 元素内部 HTML</li>
     *   <li>{@code attr:href} —— 取属性</li>
     *   <li>其它任意 CSS —— 在元素内部再选一次并取文本</li>
     * </ul>
     */
    public static final class ListRule {
        /** URL 模板，可用 {@code |} 分隔多个。 */
        public final String url;
        /** 条目 CSS 选择器。 */
        public final String item;
        /** 标题取值表达式，默认 {@code text}。 */
        public final String title;
        /** 作者取值表达式，可空。 */
        public final String authorExpr;
        /** 定位串取值表达式，可空（缺省用标题本身）。 */
        public final String key;
        /** 是否按关键字在本地过滤（搜索页不支持服务端搜索时用）。 */
        public final boolean filterKeyword;
        /** {@code {page}} 展开的页数（含第 1 页）。 */
        public final int pages;
        /** 条目原始定位串以这些前缀开头时跳过（剔除站内导航/推荐位/外链）。 */
        public final List<String> skipHrefPrefix;

        ListRule(String url, String item, String title, String authorExpr, String key,
                 boolean filterKeyword, int pages, List<String> skipHrefPrefix) {
            this.url = url;
            this.item = item;
            this.title = title;
            this.authorExpr = authorExpr;
            this.key = key;
            this.filterKeyword = filterKeyword;
            this.pages = pages;
            this.skipHrefPrefix = skipHrefPrefix;
        }
    }

    /** 正文规则。 */
    public static final class ContentRule {
        /** URL 模板（通常就是 {@code {chapterKey}}）。 */
        public final String url;
        /** 段落选择器：命中多个节点，每个节点的文本作为一段。 */
        public final String item;
        /** 整块正文选择器：命中 1 个节点，按其内部 {@code <p>}/{@code <br>} 切段。 */
        public final String container;
        /** 要剔除的选择器（广告、版权声明等），按「段落文本完全相同」跳过。 */
        public final List<String> remove;

        ContentRule(String url, String item, String container, List<String> remove) {
            this.url = url;
            this.item = item;
            this.container = container;
            this.remove = remove;
        }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 解析一份规则 JSON。
     *
     * @param json 规则文本（允许带 UTF-8 BOM）
     * @return 不可变规则对象
     * @throws IOException 文本不是 JSON、根节点不是对象、必填字段缺失、超时值非法等；
     *                     异常消息是给用户看的中文，会原样进日志与界面
     */
    public static SourceRule parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("规则内容为空");
        }
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseString(stripBom(json));
        } catch (RuntimeException e) {
            throw new IOException("不是合法 JSON：" + e.getMessage(), e);
        }
        if (!rootEl.isJsonObject()) {
            throw new IOException("规则根节点必须是 JSON 对象");
        }
        JsonObject root = rootEl.getAsJsonObject();

        String name = text(root, "name");
        String id = text(root, "id");
        if (id.isEmpty()) {
            // 兼容「只写了 name」的手写规则：用书名号外的名字当 id
            id = name.isEmpty() ? "" : idFromName(name);
        }
        if (id.isEmpty()) {
            throw new IOException("缺少 id（书源唯一标识），且 name 也为空");
        }
        if (name.isEmpty()) {
            name = id;
        }

        int timeout = intValue(root, DEFAULT_TIMEOUT_MS, "timeoutMs", "timeout_ms");
        timeout = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeout));

        JsonObject searchObj = object(root, "search");
        if (searchObj == null) {
            throw new IOException("缺少 search 段");
        }
        ListRule search = listRule(searchObj, "search");
        JsonObject chaptersObj = object(root, "chapters");
        if (chaptersObj == null) {
            throw new IOException("缺少 chapters 段");
        }
        ListRule chapters = listRule(chaptersObj, "chapters");
        JsonObject contentObj = object(root, "content");
        if (contentObj == null) {
            throw new IOException("缺少 content 段");
        }
        ContentRule content = contentRule(contentObj);

        return new SourceRule(
                id,
                name,
                text(root, "author"),
                text(root, "baseUrl", "base_url"),
                headers(root),
                timeout,
                search,
                chapters,
                content);
    }

    private static ListRule listRule(JsonObject o, String section) throws IOException {
        String url = text(o, "url");
        if (url.isEmpty()) {
            throw new IOException(section + ".url 为空");
        }
        String item = text(o, "list", "item");
        if (item.isEmpty()) {
            throw new IOException(section + ".list（CSS 选择器）为空");
        }
        String title = text(o, "title");
        if (title.isEmpty()) {
            title = "text";
        }
        String authorExpr = text(o, "author");
        if (authorExpr.isEmpty()) {
            authorExpr = null;
        }
        String key = text(o, "key");
        if (key.isEmpty()) {
            key = null;
        }
        boolean filter = boolValue(o, false, "filterKeyword", "filter_keyword");
        int pages = intValue(o, 1, "pages");
        if (pages < 1) {
            pages = 1;
        }
        if (pages > 50) {
            pages = 50; // 防止手写规则把 {page} 展开成上千次请求
        }
        List<String> skip = stringList(o, "skipHrefPrefix", "skip_href_prefix");
        return new ListRule(url, item, title, authorExpr, key, filter, pages, skip);
    }

    private static ContentRule contentRule(JsonObject o) throws IOException {
        String url = text(o, "url");
        if (url.isEmpty()) {
            throw new IOException("content.url 为空");
        }
        String item = text(o, "list", "item");
        String container = text(o, "container");
        if (item.isEmpty() && container.isEmpty()) {
            throw new IOException("content 段至少要给 list（段落选择器）或 container（正文容器选择器）之一");
        }
        return new ContentRule(url,
                item.isEmpty() ? null : item,
                container.isEmpty() ? null : container,
                stringList(o, "remove"));
    }

    // ---------------------------------------------------------------- JSON 取值（全部容错）

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** 把展示名压成一个可当 id 用的串（中文保持原样，只去掉空白与标点）。 */
    private static String idFromName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** 依次尝试多个键名，返回第一个非空的字符串（已 trim）。 */
    private static String text(JsonObject parent, String... keys) {
        if (parent == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            JsonElement el = parent.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
                continue;
            }
            String s = el.getAsString();
            if (s != null && !s.trim().isEmpty()) {
                return s.trim();
            }
        }
        return "";
    }

    private static boolean boolValue(JsonObject parent, boolean fallback, String... keys) {
        for (String key : keys) {
            JsonElement el = parent == null ? null : parent.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
                continue;
            }
            try {
                if (el.getAsJsonPrimitive().isBoolean()) {
                    return el.getAsBoolean();
                }
                return Boolean.parseBoolean(el.getAsString().trim());
            } catch (RuntimeException ignored) {
                // 非法布尔值：继续看下一个键名
            }
        }
        return fallback;
    }

    private static int intValue(JsonObject parent, int fallback, String... keys) {
        for (String key : keys) {
            JsonElement el = parent == null ? null : parent.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
                continue;
            }
            try {
                return el.getAsInt();
            } catch (RuntimeException ignored) {
                // 非数字：回落默认值
            }
        }
        return fallback;
    }

    private static List<String> stringList(JsonObject parent, String... keys) {
        for (String key : keys) {
            JsonElement el = parent == null ? null : parent.get(key);
            if (el == null || el.isJsonNull() || !el.isJsonArray()) {
                continue;
            }
            JsonArray arr = el.getAsJsonArray();
            List<String> out = new ArrayList<>(arr.size());
            for (JsonElement item : arr) {
                if (item == null || item.isJsonNull() || !item.isJsonPrimitive()) {
                    continue;
                }
                String s = item.getAsString();
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
            return Collections.unmodifiableList(out);
        }
        return Collections.emptyList();
    }

    private static Map<String, String> headers(JsonObject root) {
        JsonObject h = object(root, "headers");
        if (h == null) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : h.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isJsonNull()) {
                continue;
            }
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive()) {
                continue;
            }
            String value = v.getAsString();
            if (value != null && !value.isEmpty()) {
                out.put(e.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        return "SourceRule{" + id + " · " + name + " @ " + baseUrl + "}";
    }

    /** 便于日志里打出规范化的源标签（小写去空白）。 */
    public String normalizedId() {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
