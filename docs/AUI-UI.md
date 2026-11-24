# 晴雪UI（ApricityUI）面板集成

> 事实源。改这块代码前先读本文，否则会踩「屏幕打开但一片空白、日志零报错」的坑。

## 1. 目标与形态

双渲染路径，同一套功能：

| 环境 | 面板实现 | 技术 |
|---|---|---|
| 装了 晴雪UI（`apricityui`） | HTML + CSS 单页多视图 | `ApricityScreen`，套 `ore` 主题（基岩版观感） |
| 没装 / 页面加载失败 | 现有原版界面 | MC 原生 `Screen` + `GuiGraphics` 手绘（`LocalReaderScreen`） |

入口 `M` 键：`AuiBridge.open()` 返回非 null 就用 HTML 面板，否则退回原版界面。
`pauseOnGui` 判定同时认两种面板（`AuiBridge.isOurs(screen)`）。

`ore` 就是「基岩版主题」——Minecraft 基岩版官方 UI 框架名为 **OreUI**，jar 里搜 `bedrock` 永远是 0 命中。
`theme/ore/ore.css` 的变量即基岩版调色板（`--canvas:#202124` `--surface:#48494a` `--green:#3c8527` `--gold:#f0b92d` `--ink:#f4f5f7`），
字体 `OreRegular=minecraft-regular.otf`、`OreDisplay=minecraft-ten.ttf`。

## 2. 硬约束（踩过的坑）

1. **资源路径被硬编码为命名空间 `apricityui`**。HTML 物理路径必须是
   `src/main/resources/assets/apricityui/apricity/readerbridge/ui/main.html`
   （两段 `apricityui`：外层是 assets 命名空间，内层是引擎目录），逻辑路径 `readerbridge/ui/main.html`。
2. **逻辑路径绝不能带冒号**。`ResourceService.parseLocation` 对含 `:` 的输入走
   `ResourceLocation.tryParse`，含 `/` 判非法 → 返回 `Optional.empty()` →
   **屏幕照常打开、内容纯空白、日志零报错**。这是最难 debug 的静默失败。
3. **可选前置必须做到零耦合**：
   - 构建期 `compileOnly files('libs/apricityui-forge-1.20.1-1.3.1.jar')`，**不得 embed**。
   - 运行期 `ModList.get().isLoaded("apricityui")` 守卫，且 AUI 类型只允许出现在
     `client/ui/AuiBridge.java`、`client/ui/AuiScreens.java` 内部（字段/方法签名/`static{}`/`extends` 一律不许出现）。
   - jar 自检：`com/sighs/*` 条目数必须为 0。理由同 jsoup 事故——
     把第三方类平铺进 mod jar 会让本模组导出别人的包，与整合包内其它 mod 撞包，
     Java 模块系统在 mod 扫描前抛 `ResolutionException`，**游戏启动即死**。
4. 主题切换只影响本页 `Document`，不会污染别的 mod 的 AUI 页面。

## 3. 已验证据（2026-11-25 五视图构建）

```
BUILD SUCCESSFUL in 17s                       GRADLE_EXIT=0，0 error 0 warning
jar  = forge-bridge/build/libs/readerbridge-1.0.0.jar
       541,639 B / 259 entries
SIGHS_ENTRIES = 0        （可选前置未打进 jar）
JSOUP_ENTRIES = 0        （历史事故指标，持续守护）
HTML_MAIN     = 1        assets/apricityui/apricity/readerbridge/ui/main.html
AUICLASS      = client/ui/AuiBridge.class, client/ui/AuiScreens.class
SHA256        = A830B6D1E6AB63E90A52A282293B9A2A9FD3C116641FDD43FDFCFBFC97BA347E
```

三处部署字节一致（`MATCH=True`）：
- `forge-bridge\build\libs\readerbridge-1.0.0.jar`
- `E:\MC\versions\What Fucking This Game\mods\readerbridge-1.0.0.jar`
- `D:\切换桌面文件夹\福建新厝项目桌面\readerbridge-1.0.0.jar`

## 3.1 五视图结构（首版只有播放条，已补齐）

> **本节描述的是第二版实现（纯 Java 注入固定槽位、页面零 JS、视图用 `left:-9999px` 移位），
> 已被 §3.2 取代，保留作历史对照。** 该版实机只显示播放条，原因见 §3.2 开头的根因分析。

单页 `main.html`，底部常驻播放条，上方五个视图：

| 视图 | 槽位前缀 | 数据来源 | 行点击动作 |
|---|---|---|---|
| 书架 | `rb-sl-0..6` | `LibraryStore.entries()`（按 `updatedAtMs` 降序） | `ReaderEngine.resumeEntry(entry)` → 切目录 |
| 搜索 | `rb-sr-0..6` | `ReaderEngine.search(kw)`，**后台线程**，结果经 `Minecraft.execute` 回主线程 | `openBook(book)` → 切目录 |
| 目录 | `rb-ch-0..6` + 上页/下页/页码 | `ReaderEngine.chapters()`（`▶` 标当前章） | `openChapter(index)` → 切正文 |
| 正文 | `rb-rd-0..7` | `ReaderEngine.sentences()` 的「当前句 ±4」窗口 | `seekSentence(base+i)` |
| 设置 | `rb-set-*` | `speedLabel/voiceLabel/voices/BookSources.totalSourceCount()` | 切语速 / 切音色 / 停止 |

实现上的三个取舍（都是被引擎限制逼出来的）：

1. **视图切换不用 `display`**，用绝对定位 `left:-9999px` 移出画面 —— `display` 的支持性未经证实，
   而 `position/left` 已在旧版卡片上验证可用。
2. **列表用固定槽位 `setTextContent` 灌内容，不做动态 DOM 增删** —— 只依赖三个已验证的 DOM 操作：
   `getElementById` / `setTextContent` / `setInlineStyleProperty`。行高固定，空串即最后一屏的留白，不跳版。
3. **页面零 JS** —— 本机不一定装 KubeJS/Rhino，没有时脚本整段被跳过；交互全由 Java 侧
   `Element.addEventListener("click", ...)` 绑定。

状态刷新有签名去重（`tab|页码|active|paused|书名|章名|句|进度|语速|音色|status|数据签名`），
签名不变整帧跳过 —— AUI 的 `setTextContent` 会触发排版，每 tick 无脑重写会掉帧。
数据签名按视图分别编（书架用 `updatedAtMs` 累加、搜索用命中数+提示、目录用章数+当前章……），
所以「同一本书进度变了但数量没变」也能被捕捉到。

`AuiBridge.close()` 现在会调 `AuiScreens.clear()`，且在 `available()` 守卫内 —— 没装晴雪UI 时绝不触碰 AUI 类型。

## 3.2 第三版：HTML 骨架 + 单页 JS 渲染（当前实现）

### 为什么推倒重来

第二版实机只看到播放条。逐项核对权威说明（`doc.sighs.cc/ApricityUI/skill`，作者写给 AI 的自包含文档）后确认三个硬缺失：

| 缺失项 | 后果 |
|---|---|
| `<meta name="aui-mouse-events" content="intercept">` | 鼠标事件不落在页面上，**点击穿透到游戏**，按钮全失效 |
| `<meta name="aui-viewport" content="mode=browser">` | 逻辑视口不受控，绝对定位的五个视图摊在一张超大画布上，只能看见播放条那一块 |
| `<meta name="aui-font-mode" content="web">` | 字体走像素 atlas，中文字形缺失 |

同页面 `<script>` 数为 0（纯 Java 注入）—— 也就是把该用 JS 的部分全压在 Java 侧，反而放大了上面的问题。

### 现在的分层

- **HTML**：静态骨架，三个 meta 齐备，固定 8 个行槽位 `#row0..#row7`（不做动态 DOM 增删，Java 绑定事件时元素必须已存在）。
- **JS（Rhino）**：`setInterval(pull, 80)` 轮询隐藏容器 `#rb-data` 的文本内容，变了才 `render()` 重排。JS 只读不写回。
- **Java**：每 150ms 在客户端主线程把引擎状态序列化成**一份** `␟` 分隔的数据块写进 `#rb-data`。所有的点击/输入仍由 Java 侧 `Element.addEventListener` 绑定，搜索框用 `Element.getValue()` 读值。

数据块字段（`f[0]` 是版本号）：

| 下标 | 内容 | 下标 | 内容 |
|---|---|---|---|
| 0 | ver（自增） | 10 | 行 6 |
| 1 | tab 索引 0..4 | 11 | 行 7 |
| 2 | vhead（视图标题+计数） | 12 | vfoot |
| 3 | 占位 `" "` | 13 | 播放条标题 |
| 4..11 | 行 0..7 | 14 | 进度百分比 |
| | | 15 | 播放按钮文字 |
| | | 16 | 语速标签 |
| | | 17 | 音色标签 |
| | | 18 | 状态行 |
| | | 19 | 关键词 |

### 仍保留的设计取舍

1. **分隔符用可见符号 `␟`(U+241F) / `␞`(U+241E)，不用控制字符** —— 控制字符可能被文本节点规范化吃掉；这两个符号在中文书里几乎不可能出现。
2. **不用 `var()` / `calc()` / Ore 组件类** —— wheel 上的类名（`.button`/`.card`）与 jar 1.3.1 内的（`.button-primary-2`/`.card-2`）不一致，颜色全部硬编码，`ore.css` 只用来拿 reset 与字体。
3. **JS 不回传指令** —— `Event.detail` 虽是 public 字段，但 Rhino 侧 `CustomEvent` 构造器未确认；全部点击走 Java 绑定是零风险路径。
4. **`AuiScreens.refresh()` 用 `screen == null || mc.screen != screen` 自检退出** —— 不依赖 `ClientForgeEvents` 的 tick 回调，避免侵入原版界面路径。

## 3.3 已修致命 bug：装了晴雪UI 却仍开原版面板

实机现象：**界面还是原版 `LocalReaderScreen`**，日志里只有

```
[readerbridge] [AUI Open] com.sighs.apricityui.screen.ApricityScreen@55789a1f stage=REQUEST
[readerbridge] 客户端界面已注册: readerbridge:screen/local_reader
```

`stage=REQUEST` 是 `new ApricityScreen(...)` 构造时打的，之后**再无任何 stage**，说明屏幕没被真正 `setScreen`。

根因（`AuiScreens.open()` 自己造成的）：

1. `open()` 新建 screen 后紧接着调了 `refresh()`；
2. 但此刻调用方还没执行 `minecraft.setScreen(screen)`，所以 `mc.screen != screen` 必然成立；
3. `refresh()` 的自检分支 `if (screen == null || mc.screen != screen) { screen = null; return; }` 把**静态字段**清成 null；
4. `open()` 结尾 `return screen;` 读的是静态字段 → **返回 null**；
5. 调用方 `Screen auiPanel = AuiBridge.open(); setScreen(auiPanel != null ? auiPanel : new LocalReaderScreen());` 于是走了回退分支。

修法：`open()` 里不再调 `refresh()`，改为先把引用存进局部 `final Screen opened`，首帧渲染与事件绑定交给 pump 的首个 tick（150ms 后，此时 `init()` 已完成、`mc.screen == screen` 成立）。同时新建 screen 时清空 `lastBody`，避免新文档沿用旧签名跳过首帧。

**教训**：这类「自检是否该销毁自己」的逻辑，不能调用于对象尚未进入正式状态的构造期 —— 判据 `mc.screen != screen` 在 `open()` 时刻永远为真。自检只能由 pump/tick 驱动。

本轮构建证据：`541,658 B` / `259 entries` / `SIGHS=0` / `JSOUP=0` / `HTML_MAIN=1`，
SHA256 `28FC1AADA0B434ABC0189E7D824BFDC751BA133E051F126C6E25D03E30F7494B`，mods 与桌面两处 `MATCH=True`。

## 3.4 第四版：全屏自适应 + 点开就读 + 设置页写回（2026-11-25）

四个实机反馈与对应改法：

| 症状 | 根因 | 改法 |
|---|---|---|
| 面板缩在左上角、只占约 1/4 屏 | `#app` 写死 `820×452` 像素，而 `aui-viewport=mode=browser` 的逻辑视口 = 游戏窗口像素（1920×1080） | `#app{left:0;top:0;width:100%;height:100%}`；所有 `796px` 宽度改 `96%`；贴右元素（关闭键、翻页键）改百分比 left；播放条由 `top:352px` 改 `bottom:0` |
| 目录页滚动被劫持，整页都在滑 | body 未禁滚动，内容超出视口时引擎滚的是页面 | `html,body{overflow:hidden}`（面板是全屏固定布局，内部永不溢出） |
| 什锦铺开着能搜到书，点「阅读」拿不到正文 | 搜索结果行只调 `openBook(book)`（只取目录、不开章）；目录是异步拉的，调用方立刻 `openChapter` 会被越界检查静默 return，**无任何报错** | `ReaderEngine` 加 `openBook(BookInfo, boolean autoOpenFirst)`；目录到手后若没有 `pendingResume` 就自动 `openChapter(0,0)`。面板点搜索结果传 `true`，有续读请求时仍优先续读 |
| 设置页只是文字，不能改 | `settingsRow()` 只读展示 | `clickRow` 加 `case TAB_SETTINGS -> clickSetting(at)`；第 4 行（3D 文字）点击即 `setShowHud(!showHud)` 写回 toml。端口/重连间隔故意不做交互 —— 会重建 WebSocket、掐断朗读 |

硬约束复用（改 CSS 前必读）：AUI **不支持 `calc()`、`transform`**，所以「贴右」只能用百分比 `left`，「撑满」只能用 `100%`，不能用 `calc(100% - 36px)`。百分比长度解析已被 `#p-fill{width:0%}` + JS 内联百分比实证可用。

本轮构建证据：`JAR_BYTES=542401`、`ENTRIES=259`、`SIGHS=0`、`JSOUP=0`、`HTML_MAIN=1`、`AUI=1`（仅 `AuiScreens.class`，无内部类）；SHA256 `AC76D645E8F4CF3A077EE41F57F3D1A3458CA7E2C4D51533DE6E36B5843BBDE9`；mods 与桌面两处 `MATCH=True`。

## 4. 待游戏内实测（离线无法覆盖）

| 项 | 预期 |
|---|---|
| 装晴雪UI 按 `M` | 打开 ore 主题 HTML 面板，非原版界面 |
| 移除晴雪UI 按 `M` | 回退原版界面，功能不缺失 |
| 页面渲染 | 中文字形可见（ore 自带字体只有拉丁字形，中文走系统 AWT 回退） |
| 事件回调 | `Element.addEventListener(String, Consumer<Event>)`，Java 侧监听，页面 JS 在无 KubeJS/Rhino 时整段跳过但页面照常渲染 |
| ESC | 自行监听 `keydown` `keyCode == 256` 主动 `onClose()`（不保证冒泡） |
| Resources 重载 | `Document` 可能被重建，旧 `Element` 引用失效，需 `Document.isActive()` / `getRefreshGeneration()` 防御 |

## 5. 引擎能力边界（决定画面能画到什么程度）

不支持：`text-shadow`、`radial-gradient` / `conic-gradient`、`:has()`、`attr()`、
`calc()` 的乘除、负 `margin`、`currentColor`、`visibility` 过渡。
无 UA 样式：`h1` 等价 `div`，`ul` 无圆点，`table` 无表格布局。
`fetch` 只接受单个参数 `fetch(url)`，没有 init / method / headers / body。

能用：`.button-primary-2/-3`、`.icon-button`、`.card-2`、`.panel-2`、`.form-input-2`、
`.switch`、`.checkbox`、`.radio`、`.slider`、`.tooltip`、`.dropdown`、`.toast`、
`.loading-mask`、`.progress-2`、`.tag`、`.badge-2`、`.list-group-2`、`.drawer`、
`.sidebar`、`.scrollbar`、`.tab-2/-3`（见 `theme/ore/example.html`，七页组件展示）。

## 6. 参考

- `.reports/apricityui.md`（1288 行，990 class 反编译级调研，含 10 条不确定项）
- `.reports/fqapp-ui.md`（645 行，什锦铺 42 界面 → 9 Screen 功能矩阵）
- 快照 tag：`backup_20261124_2358_before_aui_ui`
