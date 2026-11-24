package com.feiyu.readerbridge.client.source;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 极简 HTML 解析器 + CSS 选择器引擎（纯 JDK，无第三方依赖）。
 *
 * <p>存在理由：把 jsoup 平铺进 mod jar 会让 {@code readerbridge} 模块导出 {@code org.jsoup.*} 包，
 * 与整合包里其它自带 jsoup 的模组冲突，Java 模块系统在 mod 扫描之前就抛
 * {@code java.lang.module.ResolutionException} 直接把游戏打死。所以这里只实现
 * 「够解析真实书源页面」的最小子集。
 *
 * <p>支持：{@code tag} / {@code .class} / {@code #id} / {@code *} / 复合（{@code tag.class#id}）/
 * 后代（空格）/ 子元素（{@code >}）/ 逗号分组 / 属性筛选
 * {@code [a]} {@code [a=v]} {@code [a^=v]} {@code [a$=v]} {@code [a*=v]}（值可带引号）/
 * 伪类 {@code :first-child} {@code :last-child} {@code :nth-child(n)} {@code :nth-last-child(n)}
 * {@code :first-of-type} {@code :last-of-type} {@code :nth-of-type(n)} {@code :not(<上述任一简单选择器>)}。
 *
 * <p>不支持（会抛 {@link IllegalArgumentException}，由调用方跳过该源并打日志）：
 * 兄弟组合符 {@code +} {@code ~}、{@code :has()} {@code :contains()} {@code :eq()} 等 jQuery 扩展、
 * {@code :nth-child(an+b)} 表达式、属性大小写不敏感标志 {@code [a=v i]}。
 *
 * <p>解析器容错：未闭合标签按栈回溯闭合；{@code p}/{@code li}/{@code td}/{@code tr}/{@code option}/{@code dt}
 * 做 HTML5 式隐式闭合；{@code <br>} 等空元素自闭合；注释 / DOCTYPE / 处理指令跳过；
 * {@code script}/{@code style} 内容整体丢弃（不会污染正文文本）。
 */
final class MiniHtml {

    private MiniHtml() {
    }

    // ================================================================ 节点

    /** 一个节点：{@code tag == null} 表示文本节点。 */
    static final class Node {
        private final String tag;              // 小写标签名；文本节点为 null
        private final String textContent;      // 文本节点内容（已反转义）；元素节点为 null
        private final List<String[]> attrs;    // 元素属性 name/value（value 已反转义）
        private final List<Node> children;
        Node parent;

        Node(String tag, List<String[]> attrs) {
            this.tag = tag;
            this.textContent = null;
            this.attrs = attrs == null ? new ArrayList<>(0) : attrs;
            this.children = new ArrayList<>(4);
        }

        private Node(String text) {
            this.tag = null;
            this.textContent = text;
            this.attrs = new ArrayList<>(0);
            this.children = new ArrayList<>(0);
        }

        boolean isText() {
            return tag == null;
        }

        /** 子节点（文本 + 元素），按文档顺序。 */
        List<Node> childNodes() {
            return children;
        }

        /** 小写标签名；文本节点返回空串。 */
        String normalName() {
            return tag == null ? "" : tag;
        }

        boolean hasAttr(String name) {
            if (name == null || tag == null) {
                return false;
            }
            String n = name.toLowerCase(Locale.ROOT);
            for (String[] a : attrs) {
                if (a[0].equals(n)) {
                    return true;
                }
            }
            return false;
        }

        /** 取属性值（不存在返回空串，与 jsoup 一致）。 */
        String attr(String name) {
            if (name == null || tag == null) {
                return "";
            }
            String n = name.toLowerCase(Locale.ROOT);
            for (String[] a : attrs) {
                if (a[0].equals(n)) {
                    return a[1] == null ? "" : a[1];
                }
            }
            return "";
        }

        /** 元素自身 + 全部后代的文本，块级边界补空格后折叠空白。 */
        String text() {
            StringBuilder sb = new StringBuilder(64);
            appendText(this, sb);
            return collapse(sb.toString());
        }

        /** 未折叠的原始文本（保留换行），供按行切段用。 */
        String rawWholeText() {
            StringBuilder sb = new StringBuilder(64);
            appendText(this, sb);
            return sb.toString();
        }

        /** 元素内部 HTML（innerHTML）。 */
        String html() {
            StringBuilder sb = new StringBuilder(64);
            for (Node c : children) {
                c.appendHtml(sb);
            }
            return sb.toString();
        }

        private void appendHtml(StringBuilder sb) {
            if (tag == null) {
                escapeInto(textContent == null ? "" : textContent, sb);
                return;
            }
            sb.append('<').append(tag);
            for (String[] a : attrs) {
                sb.append(' ').append(a[0]);
                if (a[1] != null && !a[1].isEmpty()) {
                    sb.append("=\"");
                    escapeInto(a[1], sb);
                    sb.append('"');
                }
            }
            sb.append('>');
            for (Node c : children) {
                c.appendHtml(sb);
            }
            sb.append("</").append(tag).append('>');
        }

        private static void appendText(Node node, StringBuilder sb) {
            if (node.tag == null) {
                if (node.textContent != null) {
                    sb.append(node.textContent);
                }
                return;
            }
            if ("br".equals(node.tag)) {
                sb.append(' ');
                return;
            }
            boolean block = BLOCK_ELEMENTS.contains(node.tag);
            if (block) {
                sb.append(' ');
            }
            for (Node c : node.children) {
                appendText(c, sb);
            }
            if (block) {
                sb.append(' ');
            }
        }

        @Override
        public String toString() {
            return tag == null ? "#text(" + textContent + ")" : "<" + tag + ">";
        }
    }

    // ================================================================ 解析

    /** 解析 HTML 字节流（自动按 BOM / {@code <meta charset>} 猜编码）。 */
    static Node parse(byte[] body) {
        Node root = new Node("#root", null);
        if (body == null || body.length == 0) {
            return root;
        }
        String html = decode(body);
        new Parser(html, root).run();
        return root;
    }

    /** 解析一段已经解码好的 HTML（给单测/自检用）。 */
    static Node parse(String html) {
        Node root = new Node("#root", null);
        if (html == null || html.isEmpty()) {
            return root;
        }
        new Parser(html, root).run();
        return root;
    }

    private static final class Parser {
        private final String s;
        private final Node root;
        private final List<Node> stack = new ArrayList<>();
        private int i;

        Parser(String s, Node root) {
            this.s = s;
            this.root = root;
            this.stack.add(root);
        }

        void run() {
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c != '<') {
                    int next = s.indexOf('<', i);
                    if (next < 0) {
                        next = n;
                    }
                    if (next > i) {
                        appendText(s.substring(i, next));
                    }
                    i = next;
                    continue;
                }
                if (s.startsWith("<!--", i)) {
                    int end = s.indexOf("-->", i + 4);
                    i = end < 0 ? n : end + 3;
                    continue;
                }
                if (i + 1 < n && (s.charAt(i + 1) == '!' || s.charAt(i + 1) == '?')) {
                    int end = s.indexOf('>', i);
                    i = end < 0 ? n : end + 1;
                    continue;
                }
                if (i + 1 < n && s.charAt(i + 1) == '/') {
                    endTag();
                    continue;
                }
                startTag();
            }
        }

        private void appendText(String raw) {
            String text = unescape(raw);
            if (text.isEmpty()) {
                return;
            }
            Node cur = stack.get(stack.size() - 1);
            Node last = cur.children.isEmpty() ? null : cur.children.get(cur.children.size() - 1);
            if (last != null && last.isText()) {
                // 相邻文本节点合并（实体拆分时会出现）
                cur.children.set(cur.children.size() - 1, new Node(last.textContent + text));
                return;
            }
            Node textNode = new Node(text);
            textNode.parent = cur;
            cur.children.add(textNode);
        }

        private void endTag() {
            int n = s.length();
            int p = i + 2;
            while (p < n && isNameChar(s.charAt(p))) {
                p++;
            }
            String tag = s.substring(Math.min(i + 2, n), p).toLowerCase(Locale.ROOT);
            int close = s.indexOf('>', p);
            i = close < 0 ? n : close + 1;
            if (tag.isEmpty()) {
                return;
            }
            // 从栈顶往下找最近的同名标签，弹到它为止（容错）
            for (int k = stack.size() - 1; k >= 1; k--) {
                if (tag.equals(stack.get(k).tag)) {
                    while (stack.size() > k) {
                        stack.remove(stack.size() - 1);
                    }
                    return;
                }
            }
            // 找不到同名开标签：多数是 <p> 之类的多余闭合，忽略
        }

        private void startTag() {
            int n = s.length();
            int p = i + 1;
            while (p < n && isNameChar(s.charAt(p))) {
                p++;
            }
            String tag = s.substring(Math.min(i + 1, n), p).toLowerCase(Locale.ROOT);
            if (tag.isEmpty()) {
                appendText("<");
                i++;
                return;
            }
            List<String[]> attrs = new ArrayList<>(4);
            boolean selfClose = false;
            while (p < n) {
                char ch = s.charAt(p);
                if (ch == '>') {
                    p++;
                    break;
                }
                if (ch == '/') {
                    if (p + 1 < n && s.charAt(p + 1) == '>') {
                        selfClose = true;
                        p += 2;
                        break;
                    }
                    p++;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    p++;
                    continue;
                }
                int nameStart = p;
                while (p < n) {
                    char cc = s.charAt(p);
                    if (cc == '=' || cc == '>' || Character.isWhitespace(cc)) {
                        break;
                    }
                    if (cc == '/' && p + 1 < n && s.charAt(p + 1) == '>') {
                        break;
                    }
                    p++;
                }
                String attrName = s.substring(nameStart, p).toLowerCase(Locale.ROOT);
                if (attrName.isEmpty()) {
                    p++;
                    continue;
                }
                while (p < n && Character.isWhitespace(s.charAt(p))) {
                    p++;
                }
                String attrValue = "";
                if (p < n && s.charAt(p) == '=') {
                    p++;
                    while (p < n && Character.isWhitespace(s.charAt(p))) {
                        p++;
                    }
                    if (p < n && (s.charAt(p) == '"' || s.charAt(p) == '\'')) {
                        char quote = s.charAt(p++);
                        int vs = p;
                        while (p < n && s.charAt(p) != quote) {
                            p++;
                        }
                        attrValue = s.substring(vs, Math.min(p, n));
                        if (p < n) {
                            p++;
                        }
                    } else {
                        int vs = p;
                        while (p < n) {
                            char cc = s.charAt(p);
                            if (Character.isWhitespace(cc) || cc == '>') {
                                break;
                            }
                            p++;
                        }
                        attrValue = s.substring(vs, p);
                    }
                }
                attrs.add(new String[]{attrName, unescape(attrValue)});
            }
            i = p;

            // script/style/template 内容整段丢弃：它们不该出现在书名或正文里
            if ("script".equals(tag) || "style".equals(tag) || "template".equals(tag)) {
                int end = indexOfIgnoreCase("</" + tag, i);
                if (end < 0) {
                    i = n;
                    return;
                }
                int gt = s.indexOf('>', end);
                i = gt < 0 ? n : gt + 1;
                return;
            }

            autoClose(tag);

            Node el = new Node(tag, attrs);
            Node cur = stack.get(stack.size() - 1);
            el.parent = cur;
            cur.children.add(el);

            if (selfClose || VOID_ELEMENTS.contains(tag)) {
                return;
            }
            if ("textarea".equals(tag) || "title".equals(tag)) {
                int end = indexOfIgnoreCase("</" + tag, i);
                if (end < 0) {
                    i = n;
                    return;
                }
                String raw = s.substring(i, end);
                if (!raw.isEmpty()) {
                    Node textNode = new Node(unescape(raw));
                    textNode.parent = el;
                    el.children.add(textNode);
                }
                int gt = s.indexOf('>', end);
                i = gt < 0 ? n : gt + 1;
                return;
            }
            stack.add(el);
        }

        /** HTML5 式隐式闭合：{@code <p>} 遇块级、{@code <li>} 遇 {@code <li>} 等。 */
        private void autoClose(String tag) {
            if ("li".equals(tag)) {
                closeOpen(new String[]{"li"}, new String[]{"ul", "ol", "menu"});
            } else if ("p".equals(tag) || CLOSES_P.contains(tag)) {
                closeOpen(new String[]{"p"}, CONTAINER_TAGS);
            }
            if ("dt".equals(tag) || "dd".equals(tag)) {
                closeOpen(new String[]{"dt", "dd"}, new String[]{"dl"});
            } else if ("td".equals(tag) || "th".equals(tag)) {
                closeOpen(new String[]{"td", "th"}, new String[]{"tr", "table"});
            } else if ("tr".equals(tag)) {
                closeOpen(new String[]{"tr"}, new String[]{"table", "tbody", "thead", "tfoot"});
            } else if ("option".equals(tag)) {
                closeOpen(new String[]{"option"}, new String[]{"select", "optgroup"});
            }
        }

        /**
         * 从栈顶向下扫：遇到 {@code barriers} 里的标签就停（不跨容器闭合），
         * 遇到 {@code targets} 里的标签就把它及其子压栈弹出。
         */
        private void closeOpen(String[] targets, String[] barriers) {
            for (int k = stack.size() - 1; k >= 1; k--) {
                String t = stack.get(k).tag;
                if (contains(targets, t)) {
                    while (stack.size() > k) {
                        stack.remove(stack.size() - 1);
                    }
                    return;
                }
                if (contains(barriers, t)) {
                    return;
                }
            }
        }

        private int indexOfIgnoreCase(String needle, int from) {
            int m = needle.length();
            int last = s.length() - m;
            for (int k = Math.max(0, from); k <= last; k++) {
                if (s.regionMatches(true, k, needle, 0, m)) {
                    return k;
                }
            }
            return -1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    // ================================================================ 编码

    /** 按 BOM / {@code <meta charset>} / {@code http-equiv} 猜编码，猜不出按 UTF-8（失败回退 GB18030）。 */
    private static String decode(byte[] body) {
        Charset cs = detectCharset(body);
        if (cs != null) {
            return new String(body, cs);
        }
        String utf8 = new String(body, StandardCharsets.UTF_8);
        // UTF-8 解码出现替换字符 = 大概率其实是 GBK/GB18030 的老站
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(body, Charset.forName("GB18030"));
            } catch (RuntimeException ignored) {
                // 没有 GB18030 就用 UTF-8 的替换结果
            }
        }
        return utf8;
    }

    private static Charset detectCharset(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        int limit = Math.min(b.length, 4096);
        String head = new String(b, 0, limit, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int idx = head.indexOf("charset");
        while (idx >= 0) {
            int p = idx + 7;
            while (p < head.length() && (head.charAt(p) == '=' || head.charAt(p) == ' ' || head.charAt(p) == '"'
                    || head.charAt(p) == '\'')) {
                p++;
            }
            int start = p;
            while (p < head.length()) {
                char c = head.charAt(p);
                if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                    break;
                }
                p++;
            }
            String name = head.substring(start, p);
            if (!name.isEmpty()) {
                try {
                    Charset cs = Charset.forName(normalizeCharsetName(name));
                    return cs;
                } catch (RuntimeException ignored) {
                    // 换下一个 charset 出现位置
                }
            }
            idx = head.indexOf("charset", idx + 7);
        }
        return null;
    }

    private static String normalizeCharsetName(String name) {
        if ("gb2312".equals(name) || "gbk".equals(name) || "gb_2312-80".equals(name)) {
            return "GB18030";
        }
        if ("utf8".equals(name)) {
            return "UTF-8";
        }
        return name;
    }

    // ================================================================ 实体反转义

    private static final Map<String, String> ENTITIES = new HashMap<>(48);

    static {
        ENTITIES.put("amp", "&");
        ENTITIES.put("lt", "<");
        ENTITIES.put("gt", ">");
        ENTITIES.put("quot", "\"");
        ENTITIES.put("apos", "'");
        ENTITIES.put("nbsp", "\u00A0");
        ENTITIES.put("ensp", "\u2002");
        ENTITIES.put("emsp", "\u2003");
        ENTITIES.put("thinsp", "\u2009");
        ENTITIES.put("mdash", "\u2014");
        ENTITIES.put("ndash", "\u2013");
        ENTITIES.put("hellip", "\u2026");
        ENTITIES.put("ldquo", "\u201C");
        ENTITIES.put("rdquo", "\u201D");
        ENTITIES.put("lsquo", "\u2018");
        ENTITIES.put("rsquo", "\u2019");
        ENTITIES.put("middot", "\u00B7");
        ENTITIES.put("bull", "\u2022");
        ENTITIES.put("times", "\u00D7");
        ENTITIES.put("divide", "\u00F7");
        ENTITIES.put("plusmn", "\u00B1");
        ENTITIES.put("deg", "\u00B0");
        ENTITIES.put("copy", "\u00A9");
        ENTITIES.put("reg", "\u00AE");
        ENTITIES.put("trade", "\u2122");
        ENTITIES.put("laquo", "\u00AB");
        ENTITIES.put("raquo", "\u00BB");
        ENTITIES.put("dagger", "\u2020");
        ENTITIES.put("sect", "\u00A7");
        ENTITIES.put("para", "\u00B6");
        ENTITIES.put("euro", "\u20AC");
        ENTITIES.put("pound", "\u00A3");
        ENTITIES.put("yen", "\u00A5");
        ENTITIES.put("cent", "\u00A2");
        ENTITIES.put("sup2", "\u00B2");
        ENTITIES.put("sup3", "\u00B3");
        ENTITIES.put("frac12", "\u00BD");
        ENTITIES.put("shy", "");
        ENTITIES.put("zwnj", "");
        ENTITIES.put("zwj", "");
    }

    private static String unescape(String raw) {
        if (raw == null || raw.indexOf('&') < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = raw.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String ent = raw.substring(i + 1, semi);
            String rep = entity(ent);
            if (rep == null) {
                sb.append(c);
                i++;
                continue;
            }
            sb.append(rep);
            i = semi + 1;
        }
        return sb.toString();
    }

    private static String entity(String ent) {
        if (ent.isEmpty()) {
            return null;
        }
        if (ent.charAt(0) == '#') {
            try {
                int cp;
                if (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')) {
                    cp = Integer.parseInt(ent.substring(2), 16);
                } else {
                    cp = Integer.parseInt(ent.substring(1));
                }
                if (cp <= 0 || cp > 0x10FFFF) {
                    return null;
                }
                return new String(Character.toChars(cp));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return ENTITIES.get(ent.toLowerCase(Locale.ROOT));
    }

    private static void escapeInto(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
    }

    /** 折叠所有空白（含 {@code \u00A0} / {@code \u3000}）为单个空格并去首尾。 */
    static String collapse(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpace(c)) {
                pendingSpace = any;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
            any = true;
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B'
                || c == '\u00A0' || c == '\u3000' || c == '\u2002' || c == '\u2003' || c == '\u2009'
                || c == '\u2028' || c == '\u2029' || c == '\uFEFF';
    }

    // ================================================================ 选择器

    private static final int COMB_DESCENDANT = 0;
    private static final int COMB_CHILD = 1;

    private static final class Pseudo {
        final String name;
        final int arg;
        final Step not;

        Pseudo(String name, int arg, Step not) {
            this.name = name;
            this.arg = arg;
            this.not = not;
        }
    }

    private static final class AttrTest {
        final String name;
        final String op;   // "", "=", "^=", "$=", "*="
        final String value;

        AttrTest(String name, String op, String value) {
            this.name = name;
            this.op = op;
            this.value = value;
        }

        boolean matches(Node el) {
            if (!el.hasAttr(name)) {
                return false;
            }
            if (op.isEmpty()) {
                return true;
            }
            String actual = el.attr(name);
            switch (op) {
                case "=":
                    return actual.equals(value);
                case "^=":
                    return actual.startsWith(value);
                case "$=":
                    return actual.endsWith(value);
                case "*=":
                    return actual.contains(value);
                default:
                    return false;
            }
        }
    }

    private static final class Step {
        String tag;                       // null = 任意
        String id;
        final List<String> classes = new ArrayList<>(2);
        final List<AttrTest> attrTests = new ArrayList<>(1);
        final List<Pseudo> pseudos = new ArrayList<>(1);
        int combinator = COMB_DESCENDANT;

        boolean matches(Node el) {
            if (el == null || el.isText()) {
                return false;
            }
            String et = el.tag;
            if (et.startsWith("#")) {
                return false; // 虚拟根节点不参与匹配
            }
            if (tag != null && !tag.equals(et)) {
                return false;
            }
            if (id != null && !id.equals(el.attr("id"))) {
                return false;
            }
            if (!classes.isEmpty()) {
                String cls = el.attr("class");
                if (cls.isEmpty()) {
                    return false;
                }
                for (String want : classes) {
                    if (!hasClass(cls, want)) {
                        return false;
                    }
                }
            }
            for (AttrTest at : attrTests) {
                if (!at.matches(el)) {
                    return false;
                }
            }
            for (Pseudo p : pseudos) {
                if (!matchesPseudo(el, p)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean hasClass(String classAttr, String want) {
        int i = 0;
        int n = classAttr.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(classAttr.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(classAttr.charAt(i))) {
                i++;
            }
            if (i > start && classAttr.regionMatches(start, want, 0, want.length())
                    && i - start == want.length()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPseudo(Node el, Pseudo p) {
        switch (p.name) {
            case "first-child":
                return siblingIndex(el) == 0;
            case "last-child":
                return siblingIndex(el) == elementSiblingCount(el.parent) - 1;
            case "nth-child":
                return siblingIndex(el) == p.arg - 1;
            case "nth-last-child":
                return elementSiblingCount(el.parent) - 1 - siblingIndex(el) == p.arg - 1;
            case "first-of-type":
                return typeIndex(el) == 0;
            case "last-of-type":
                return typeIndex(el) == typeCount(el.parent, el.tag) - 1;
            case "nth-of-type":
                return typeIndex(el) == p.arg - 1;
            case "not":
                return p.not == null || !p.not.matches(el);
            default:
                return false;
        }
    }

    /** 在父节点的元素子节点里的下标（0 起）。 */
    private static int siblingIndex(Node el) {
        if (el.parent == null) {
            return 0;
        }
        int idx = 0;
        for (Node c : el.parent.children) {
            if (c.isText()) {
                continue;
            }
            if (c == el) {
                return idx;
            }
            idx++;
        }
        return idx;
    }

    private static int elementSiblingCount(Node parent) {
        if (parent == null) {
            return 1;
        }
        int count = 0;
        for (Node c : parent.children) {
            if (!c.isText()) {
                count++;
            }
        }
        return count;
    }

    private static int typeIndex(Node el) {
        if (el.parent == null) {
            return 0;
        }
        int idx = 0;
        for (Node c : el.parent.children) {
            if (c.isText()) {
                continue;
            }
            if (el.tag.equals(c.tag)) {
                if (c == el) {
                    return idx;
                }
                idx++;
            }
        }
        return idx;
    }

    private static int typeCount(Node parent, String tag) {
        if (parent == null) {
            return 1;
        }
        int count = 0;
        for (Node c : parent.children) {
            if (!c.isText() && tag.equals(c.tag)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 在 {@code root}（含自身）及其后代里查选择器。
     *
     * <p>与 jsoup 的 {@code Element.select} 一样**会把 root 自己算进结果**，
     * 调用方需要「只取后代」时自行跳过首项（{@code RuleSource#extractValue} 就是这么做的）。
     *
     * @throws IllegalArgumentException 选择器语法不认识
     */
    static List<Node> select(Node root, String css) {
        if (root == null) {
            return Collections.emptyList();
        }
        if (css == null) {
            throw new IllegalArgumentException("选择器为空");
        }
        String expr = css.trim();
        if (expr.isEmpty()) {
            throw new IllegalArgumentException("选择器为空");
        }
        List<List<Step>> groups = new ArrayList<>(1);
        for (String part : splitTopLevel(expr, ',')) {
            String t = part.trim();
            if (!t.isEmpty()) {
                groups.add(parseChain(t));
            }
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("选择器为空");
        }
        List<Node> out = new ArrayList<>(8);
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Step> chain : groups) {
            collect(root, chain, chain.size() - 1, out, seen);
        }
        return out;
    }

    private static void collect(Node node, List<Step> chain, int idx, List<Node> out, Set<Node> seen) {
        if (!node.isText() && matchesChain(node, chain, idx)) {
            if (seen.add(node)) {
                out.add(node);
            }
        }
        for (Node c : node.children) {
            if (!c.isText()) {
                collect(c, chain, idx, out, seen);
            }
        }
    }

    private static boolean matchesChain(Node el, List<Step> chain, int idx) {
        if (el == null || el.isText()) {
            return false;
        }
        Step step = chain.get(idx);
        if (!step.matches(el)) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        if (step.combinator == COMB_CHILD) {
            return matchesChain(el.parent, chain, idx - 1);
        }
        Node p = el.parent;
        while (p != null) {
            if (matchesChain(p, chain, idx - 1)) {
                return true;
            }
            p = p.parent;
        }
        return false;
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>(2);
        int depthBracket = 0;
        int depthParen = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depthBracket++;
            } else if (c == ']') {
                depthBracket--;
            } else if (c == '(') {
                depthParen++;
            } else if (c == ')') {
                depthParen--;
            } else if (c == sep && depthBracket == 0 && depthParen == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** 把一整条选择器（如 {@code div.list2 h3 > a}）解析成 step 链。 */
    private static List<Step> parseChain(String expr) {
        List<Step> chain = new ArrayList<>(4);
        int i = 0;
        int n = expr.length();
        int pendingCombinator = COMB_DESCENDANT;
        boolean sawAny = false;
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                if (sawAny) {
                    pendingCombinator = COMB_DESCENDANT;
                }
                continue;
            }
            if (c == '>') {
                if (!sawAny) {
                    throw new IllegalArgumentException("选择器不能以 > 开头：" + expr);
                }
                pendingCombinator = COMB_CHILD;
                i++;
                continue;
            }
            if (c == '+' || c == '~') {
                throw new IllegalArgumentException("不支持兄弟组合符 `" + c + "`：" + expr);
            }
            int[] cursor = {i};
            Step step = parseStep(expr, cursor, n);
            step.combinator = chain.isEmpty() ? COMB_DESCENDANT : pendingCombinator;
            chain.add(step);
            i = cursor[0];
            pendingCombinator = COMB_DESCENDANT;
            sawAny = true;
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("选择器为空");
        }
        return chain;
    }

    private static Step parseStep(String expr, int[] cursor, int n) {
        Step step = new Step();
        int i = cursor[0];
        if (i >= n) {
            throw new IllegalArgumentException("选择器不完整：" + expr);
        }
        char c = expr.charAt(i);
        if (c == '*') {
            i++;
        } else if (isIdentStart(c)) {
            int start = i;
            while (i < n && isIdentChar(expr.charAt(i))) {
                i++;
            }
            step.tag = expr.substring(start, i).toLowerCase(Locale.ROOT);
        }
        boolean consumedSomething = step.tag != null || c == '*';
        while (i < n) {
            char ch = expr.charAt(i);
            if (ch == '.') {
                i++;
                int start = i;
                while (i < n && isIdentChar(expr.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("class 名为空：" + expr);
                }
                step.classes.add(expr.substring(start, i));
                consumedSomething = true;
            } else if (ch == '#') {
                i++;
                int start = i;
                while (i < n && isIdentChar(expr.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("id 为空：" + expr);
                }
                step.id = expr.substring(start, i);
                consumedSomething = true;
            } else if (ch == '[') {
                int end = indexOfClosing(expr, i, '[', ']');
                if (end < 0) {
                    throw new IllegalArgumentException("属性选择器缺少 ]：" + expr);
                }
                step.attrTests.add(parseAttrTest(expr.substring(i + 1, end).trim(), expr));
                i = end + 1;
                consumedSomething = true;
            } else if (ch == ':') {
                i++;
                int start = i;
                while (i < n && isIdentChar(expr.charAt(i))) {
                    i++;
                }
                String name = expr.substring(start, i).toLowerCase(Locale.ROOT);
                String argText = null;
                if (i < n && expr.charAt(i) == '(') {
                    int end = indexOfClosing(expr, i, '(', ')');
                    if (end < 0) {
                        throw new IllegalArgumentException("伪类缺少 )：" + expr);
                    }
                    argText = expr.substring(i + 1, end).trim();
                    i = end + 1;
                }
                step.pseudos.add(parsePseudo(name, argText, expr));
                consumedSomething = true;
            } else {
                break;
            }
        }
        if (!consumedSomething) {
            throw new IllegalArgumentException("无法解析选择器片段：" + expr.substring(cursor[0]));
        }
        cursor[0] = i;
        return step;
    }

    private static Pseudo parsePseudo(String name, String argText, String expr) {
        switch (name) {
            case "first-child":
            case "last-child":
            case "first-of-type":
            case "last-of-type":
                return new Pseudo(name, 1, null);
            case "nth-child":
            case "nth-last-child":
            case "nth-of-type":
                return new Pseudo(name, parseNth(argText, expr), null);
            case "not": {
                if (argText == null || argText.isEmpty()) {
                    throw new IllegalArgumentException(":not() 缺少参数：" + expr);
                }
                if (argText.indexOf(' ') >= 0 || argText.indexOf('>') >= 0) {
                    throw new IllegalArgumentException(":not() 只支持单个简单选择器：" + expr);
                }
                int[] cursor = {0};
                Step inner = parseStep(argText, cursor, argText.length());
                return new Pseudo("not", 0, inner);
            }
            case "empty":
            case "root":
            case "checked":
            case "disabled":
                throw new IllegalArgumentException("不支持伪类 `:" + name + "`：" + expr);
            default:
                throw new IllegalArgumentException("不支持伪类 `:" + name + "`：" + expr);
        }
    }

    private static int parseNth(String argText, String expr) {
        if (argText == null || argText.isEmpty()) {
            throw new IllegalArgumentException(":nth-child() 缺少参数：" + expr);
        }
        try {
            int v = Integer.parseInt(argText.trim());
            if (v < 1) {
                throw new IllegalArgumentException(":nth-child() 只支持 1 起的整数：" + expr);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(":nth-child() 不支持 `" + argText + "`（只支持正整数）：" + expr);
        }
    }

    private static AttrTest parseAttrTest(String body, String expr) {
        if (body.isEmpty()) {
            throw new IllegalArgumentException("属性选择器为空：" + expr);
        }
        int opAt = -1;
        String op = "";
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '=') {
                opAt = i;
                op = "=";
                if (i > 0) {
                    char prev = body.charAt(i - 1);
                    if (prev == '^' || prev == '$' || prev == '*') {
                        op = prev + "=";
                        opAt = i - 1;
                    } else if (prev == '~' || prev == '|') {
                        throw new IllegalArgumentException("不支持属性操作符 `" + prev + "=`：" + expr);
                    }
                }
                break;
            }
        }
        if (opAt < 0) {
            return new AttrTest(body.toLowerCase(Locale.ROOT), "", "");
        }
        String name = body.substring(0, opAt).trim().toLowerCase(Locale.ROOT);
        String value = body.substring(opAt + op.length()).trim();
        if (value.length() >= 2) {
            char q = value.charAt(0);
            if ((q == '"' || q == '\'') && value.charAt(value.length() - 1) == q) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("属性名为空：" + expr);
        }
        return new AttrTest(name, op, unescape(value));
    }

    private static int indexOfClosing(String s, int from, char open, char close) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isIdentStart(char c) {
        // 注意：':' 绝不能算标识符字符 —— 否则 `p:not(:first-child)` 会被整段吃成
        // 一个 tag 名（"p:not"），伪类参数彻底解析不到。
        return Character.isLetter(c) || c == '_' || c == '-';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    // ================================================================ 标签集合

    /** 空元素（不需要闭合、不进栈）。 */
    private static final Set<String> VOID_ELEMENTS = setOf(
            "area", "base", "basefont", "br", "col", "embed", "frame", "hr", "img", "input",
            "isindex", "link", "meta", "param", "source", "track", "wbr");

    /** 遇到这些标签时，未闭合的 {@code <p>} 会被自动闭合（HTML5 规则的最小子集）。 */
    private static final Set<String> CLOSES_P = setOf(
            "address", "article", "aside", "blockquote", "dir", "div", "dl", "fieldset", "footer",
            "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p",
            "pre", "section", "table", "ul");

    /** 隐式闭合 {@code <p>} 时不允许跨越的容器。 */
    private static final String[] CONTAINER_TAGS = {
            "body", "div", "td", "th", "li", "blockquote", "section", "article", "aside", "nav",
            "table", "form", "header", "footer", "main", "dl", "dd", "dt", "figure"};

    /** 文本提取时前后补空格的块级标签。 */
    private static final Set<String> BLOCK_ELEMENTS = setOf(
            "address", "article", "aside", "blockquote", "div", "dl", "dd", "dt", "fieldset",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
            "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tbody",
            "thead", "tfoot", "tr", "td", "th", "ul");

    private static Set<String> setOf(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
