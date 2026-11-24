package com.feiyu.readerbridge.client.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 内置书源<b>模板</b>：只负责「把 jar 里的 JSON 导出到 {@code config/readerbridge/sources/}」。
 *
 * <p>历史：这三条规则原来是这个类里的 Java text block 常量，运行时直接解析。
 * 现在规则已经完全文件驱动 —— 首次启动把 jar 内模板复制到配置目录，之后
 * <b>文件是唯一事实来源</b>，用户可以随意编辑 / 删除 / 新增，程序再也不覆盖已存在的文件。
 *
 * <p>为什么搬出来：规则是联网实测过的选择器，改一个字符就可能整站失效；
 * 放在 jar 资源里（{@code readerbridge/sources/*.json}）既方便用户直接复制改，
 * 也不用为了加一条源去改 Java 代码。{@link BookSources#reload(Path)} 只扫那个目录。
 *
 * <p>各文件说明：
 * <ul>
 *   <li>{@code quanben.json} —— 全本小说网。目录必须走 AMP {@code /amp{bookPath}list.html}，
 *       普通版 {@code list.html} 只给前 47 章；正文容器 {@code div.articlebody}，
 *       首段是章节标题，故用 {@code p:not(:first-child)} 跳过。</li>
 *   <li>{@code readnovel-novel.json} —— 小说阅读网。搜索 {@code /so/{keyword}}、
 *       目录 {@code div#j-catalogWrap}、正文 {@code div.j_readContent}，
 *       末段是版权声明，故用 {@code p:not(:last-of-type)} 跳过。</li>
 *   <li>{@code shuquge-novel.json} —— 书本网。站内无搜索，用 8 个分类页拼起来再本地过滤关键词
 *       （{@code filter_keyword: true}），所以它是三者里最慢的一个。
 *       文件里带 {@code "enabled": false}，<b>默认不加载</b>：实测对一本已完结的书要 10.6 秒
 *       才出 0 结果（quanben 1.0s / readnovel 0.3s），这 10.6 秒是恒定开销，
 *       会拖死游戏内每次搜索。想用就把它改成 {@code true}。</li>
 * </ul>
 *
 * <p>导出规则：<b>已存在就跳过，绝不覆盖</b>。用户可能已经改过那份文件
 * （换域名、加 header、关掉某条源），覆盖等于把用户的修改静默丢掉。
 *
 * <p>本类不再参与「加载」：{@link BookSources} 不引用它的常量，只调用 {@link #exportMissing(Path)}。
 */
final class BuiltinSources {

    private BuiltinSources() {
    }

    /** jar 内资源目录前缀（不带前导斜杠，配合 {@code Class#getResourceAsStream}）。 */
    private static final String RESOURCE_DIR = "/readerbridge/sources/";

    /**
     * 要植入配置目录的模板文件名，<b>顺序即加载顺序</b>（搜索结果按源的顺序拼接，快的先出）。
     *
     * <p>字典序恰好也是这个顺序（quanben &lt; readnovel-novel &lt; shuquge-novel），
     * 但这里显式列出来，避免以后加源时依赖文件名排序的巧合。
     */
    private static final List<String> TEMPLATES = List.of(
            "quanben.json",
            "readnovel-novel.json",
            "shuquge-novel.json");

    /**
     * 把 jar 内的书源模板导出到 {@code configDir/sources/}：缺哪个补哪个，已存在的跳过。
     *
     * <p>只读 jar、只写自己那三个文件名，不抛异常：任何一步失败都只打一行日志，
     * 早于配置目录可用 / 只读安装目录 / 磁盘满都不该让阅读面板起不来。
     *
     * @param configDir {@code config/readerbridge}；null 时用相对路径 {@code config/readerbridge}
     *                  （与 {@code ReaderEngine.prewarm} 传进来的相对路径保持一致）
     * @return 实际新写出的文件数（0 表示全部已存在或全部失败）
     */
    static int exportMissing(Path configDir) {
        Path base = configDir != null ? configDir : Path.of("config", "readerbridge");
        Path dir = base.resolve("sources");
        int written = 0;
        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            System.err.println("[readerbridge] 书源模板：无法创建目录 " + dir + "：" + t);
            return 0;
        }
        for (String name : TEMPLATES) {
            Path target = dir.resolve(name);
            if (Files.exists(target)) {
                // 用户可能改过：绝不覆盖
                continue;
            }
            if (copyOut(name, target)) {
                written++;
            }
        }
        if (written > 0) {
            System.out.println("[readerbridge] 书源模板：已植入 " + written + " 个到 " + dir
                    + "（已存在的文件不覆盖；shuquge-novel 默认 enabled=false）");
        }
        return written;
    }

    /**
     * 单个模板的复制。先写 {@code .tmp} 再原子移动：直接写目标文件时如果进程被杀，
     * 会留下一个半截 JSON —— 而「已存在就跳过」的规则会让这个坏文件永久生效。
     */
    private static boolean copyOut(String name, Path target) {
        InputStream in = BuiltinSources.class.getResourceAsStream(RESOURCE_DIR + name);
        if (in == null) {
            System.err.println("[readerbridge] 书源模板：jar 内缺少资源 " + RESOURCE_DIR + name);
            return false;
        }
        Path tmp = target.resolveSibling(name + ".tmp");
        try (InputStream stream = in) {
            Files.write(tmp, stream.readAllBytes());
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[readerbridge] 书源模板 " + name + " 导出失败：" + e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // 清理失败无所谓，下次导出会覆盖这个 .tmp
            }
            return false;
        }
    }

    /** 模板文件名列表（只读副本，供测试/诊断打印）。 */
    static List<String> templateNames() {
        return TEMPLATES;
    }

    /** 供日志/诊断用的 UTF-8 摘要（不解析、不校验，只说明文件存在与否）。 */
    static String describe(Path configDir) {
        Path base = configDir != null ? configDir : Path.of("config", "readerbridge");
        Path dir = base.resolve("sources");
        StringBuilder sb = new StringBuilder();
        for (String name : TEMPLATES) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name).append(Files.exists(dir.resolve(name)) ? "=有" : "=无");
        }
        return sb.toString();
    }

    /** 资源文本直读（仅诊断用；加载链路一律走文件，不再读 jar）。 */
    static String readResource(String name) throws IOException {
        try (InputStream in = BuiltinSources.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IOException("缺少资源 " + RESOURCE_DIR + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
