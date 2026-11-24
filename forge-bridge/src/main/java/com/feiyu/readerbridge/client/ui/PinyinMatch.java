package com.feiyu.readerbridge.client.ui;

import java.nio.charset.Charset;

/**
 * 中文「拼音首字母」匹配（零依赖，不引任何拼音库）。
 *
 * <p><b>原理</b>：GB2312 的<b>一级汉字</b>（16~55 区，3755 个字，覆盖日常用字的 99%）
 * 在编码表里是按拼音顺序排列的。所以只要记住「每个声母的第一个字的区位码」，
 * 就能把任意一级汉字换算回声母：区位码落在哪两个相邻边界之间，就是哪个字母。</p>
 *
 * <p>例：{@code 蛊} 的 GBK 是 {@code B9C6} → 区位码 2538，落在 [2433, 2594) → {@code g}；
 * {@code 真} 是 {@code D5E6} → 5370，落在 [5249, 5600) → {@code z}；
 * 于是 {@code initialsOf("蛊真人") == "gzr"}，输入 {@code gz} 就能搜到《蛊真人》。</p>
 *
 * <p><b>已知边界</b>：二级汉字（生僻字，56~87 区）拼音无序，本类返回 0（当作不支持），
 * 书名/章节名里几乎不会出现；多音字按它在编码表里的位置取一个声母（如「重」取 z），
 * 所以「多音字另一种读法」搜不到 —— 这是首字母方案固有的代价，不做字典就绕不开。</p>
 *
 * <p>调用点：晴雪UI 面板各页的「搜索」按钮（书架按书名/作者、目录按章节名、设置按设置项）。</p>
 */
public final class PinyinMatch {

    private PinyinMatch() {
    }

    /** 23 个声母（拼音没有 i/u/v 开头的音节）；下标与 {@link #SEC} 的相邻区间一一对应。 */
    private static final String LETTERS = "abcdefghjklmnopqrstwxyz";

    /**
     * 每个声母的第一个一级汉字的 GB2312 区位码；最后一个是哨兵（55 区最后一个字之后）。
     *
     * <p>数组长度比 {@link #LETTERS} 多 1，判断时用 {@code SEC[i] <= code < SEC[i+1]}。
     */
    private static final int[] SEC = {
            1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106, 3212, 3472,
            3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558, 4684, 4925, 5249, 5600
    };

    /** GBK 一定存在于任何 JRE 里；用 Charset 常量避免每次 getBytes 抛受检异常。 */
    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 文本是否命中关键词：原文包含 <b>或</b> 拼音首字母包含。
     *
     * <p>空关键词恒真（等于不过滤），方便调用方直接拿 {@code ""} 表示「清除筛选」。
     */
    public static boolean matches(String text, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        if (text == null || text.isEmpty()) {
            return false;
        }
        String t = text.toLowerCase(java.util.Locale.ROOT);
        String k = keyword.toLowerCase(java.util.Locale.ROOT);
        if (t.contains(k)) {
            return true;
        }
        return initialsOf(t).contains(k);
    }

    /**
     * 取文本的拼音首字母串：汉字→声母，ASCII 字母数字→原样（小写），其它字符→跳过。
     *
     * <p>例：{@code "蛊真人 第4章"} → {@code "gzr4z"}。查不到声母的字（生僻字、标点）直接略过，
     * 不塞占位符，否则 {@code "gz0r"} 这种串永远搜不到。
     */
    public static String initialsOf(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            } else if (ch >= 'a' && ch <= 'z') {
                sb.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                sb.append((char) (ch + 32));
            } else {
                char letter = initialOf(ch);
                if (letter != 0) {
                    sb.append(letter);
                }
            }
        }
        return sb.toString();
    }

    /** 单个汉字的声母；非一级汉字/非汉字返回 0。 */
    private static char initialOf(char ch) {
        byte[] bs = String.valueOf(ch).getBytes(GBK);
        if (bs.length != 2) {
            return 0; // 单字节（ASCII）或无法编码的替换符
        }
        int code = ((bs[0] & 0xFF) - 0xA0) * 100 + ((bs[1] & 0xFF) - 0xA0);
        if (code < SEC[0] || code >= SEC[SEC.length - 1]) {
            return 0; // 标点符号区，或二级汉字区（拼音无序，不敢猜）
        }
        for (int i = 0; i < LETTERS.length(); i++) {
            if (code >= SEC[i] && code < SEC[i + 1]) {
                return LETTERS.charAt(i);
            }
        }
        return 0;
    }
}
