# 书源（ReaderBridge 模组侧）

本文只讲**模组**（`forge-bridge/`）的规则书源，App 侧（fqapp / Rust 核心）的书源格式见
`docs/IMPORT.md` 与 `docs/PROTOCOL.md`。

## 一句话

**首次启动会把内置书源模板植入 `config/readerbridge/sources/`，之后完全由文件驱动 —— 可以自由编辑 / 删除 / 新增。**

## 目录与文件

```
<游戏目录>/config/readerbridge/
├── readerbridge-common.toml
└── sources/
    ├── quanben.json          # 全本小说网（默认启用）
    ├── readnovel-novel.json  # 小说阅读网（默认启用）
    └── shuquge-novel.json    # 书本网（默认「不」启用，见下）
```

- 这三个文件是 **jar 内模板的副本**（资源路径 `readerbridge/sources/*.json`），
  只在文件**不存在**时写出；**已存在的文件绝不覆盖**，因为用户可能已经改过。
- 想恢复出厂模板：把对应文件删掉，重启游戏（或触发一次书源重载）。
- 想彻底不要模板：`[sources] builtinSources = false`（已存在的文件仍会被加载）。

## 加载规则

1. 扫描 `config/readerbridge/sources/*.json`，**按文件名排序**逐个加载（快源先出结果）。
2. 文件里写 `"enabled": false` 的**跳过**（目前只有 `shuquge-novel.json`）。
3. 同 `id` 出现多次时，后加载的覆盖先加载的。
4. `[sources] extraSources` 在文件之后加载，因此**同 id 时配置覆盖文件**。
   `extraSources` 的每一项可以是：绝对路径 `.json` / 该目录下的文件名 / 内联 JSON / `http(s)://` 地址。
5. 解析失败 / 站点规则失效的单个文件只跳过并打日志，不影响其它源。

## 默认启用情况

| id | 站点 | 默认 | 说明 |
| --- | --- | --- | --- |
| `quanben` | quanben.io | 启用 | 目录必须走 AMP `/amp{bookPath}list.html`（普通版只给前 47 章） |
| `readnovel-novel` | readnovel.com | 启用 | 搜索 `/so/{keyword}`，正文末段是版权声明，已剔除 |
| `shuquge-novel` | shuquge.net | **不启用** | 站内无搜索：只能抓 8 个分类页再本地过滤关键词（`filter_keyword: true`）。实测对一本已完结的书 **10.6 秒**才出 0 结果（quanben 1.0 s、readnovel 0.3 s），这 10.6 秒是恒定开销，会把每次游戏内搜索拖死 |

想启用 `shuquge-novel`：把文件里的 `"enabled": false` 改成 `"enabled": true` 后重载书源即可。
该文件顶部的 `_comment` 字段就是这段说明（加载器不认识它，随便改）。

## 什么时候重载

- 游戏启动时（`ReaderEngine.prewarm` → `BookSources.reloadAsync`）；
- `[sources] extraSources` 变化后，下一次搜索前自动重载；
- 其它情况下改完文件重启游戏最稳。

## 新增一条自己的书源

1. 复制 `quanben.json` 改名（文件名随意，`id` 必须改，且不能是 `fqapi`——那是什锦铺保留的）。
2. 改 `baseUrl` / `search` / `chapters` / `content` 的选择器。
3. 放进 `config/readerbridge/sources/` 即可。

规则字段（`id` / `name` / `baseUrl` / `headers` / `timeoutMs` / `search` / `chapters` / `content`，
以及 `title` / `key` / `filterKeyword` / `pages` / `skipHrefPrefix` / `remove` 等）以
`SourceRule.java` 的类注释为准 —— 未识别的键会被忽略，所以可以自由写注释字段。
