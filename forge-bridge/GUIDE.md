# Reader Bridge 使用指南

> 面向玩家 / 整合包作者。开发细节见 [README.md](README.md) 与 [API.md](API.md)、[PROTOCOL.md](PROTOCOL.md)。

Reader Bridge 是「肥鱼阅读器」App 的 Minecraft Forge 1.20.1 配套模组：把 App 里正在朗读的书名、章节、当前句子接到游戏里，并提供游戏内书架 / 章节 / 正文预览面板。

---

## 1. 按键表

| 默认按键 | 配置项（`config/readerbridge-common.toml`） | 作用 |
| --- | --- | --- |
| **M** | `keys.openPanel = "M"` | 打开 / 关闭阅读面板（主菜单按钮同名） |
| **G** | `keys.playPause = "G"` | 播放 / 暂停朗读 |
| **B** | `keys.nextChapter = "B"` | 下一章 |
| **N** | `keys.prevChapter = "N"` | 上一章 |
| **J** | `keys.toggleHud = "J"` | 切换屏幕左上角 HUD 显示 / 隐藏 |
| **H** | `keys.hudPosition = "H"` | 进入 HUD 位置编辑界面，拖动方框后松手自动保存 |

> **改键位需要重启游戏。** Forge 不支持运行期重新注册 `KeyMapping`，配置文件里的键名在启动时读取一次。
> 也可以在游戏内「选项 → 控制 → 按键绑定 → Reader Bridge」里改，那里由原版持久化，立刻生效。

为什么默认是 **M**：和 DangoMusic 播放器的习惯一致，且原版 Minecraft 的 M 键没有绑定。若与整合包冲突，把 `openPanel` 改成别的字母即可。

---

## 2. 阅读面板（M 键）

侧栏结构，不再平铺三栏，480p 下也可用：

```
┌─────────────────────────────────────────────┐
│ ● 已连接 47210        [重试]                 │  状态条
├──────────┬──────────────────────────────────┤
│ 书架      │  主区（由侧栏按钮切换）            │
│ 章节      │  · 书架：书名 / 作者 / 章节数      │
│ 设置      │  · 章节：章节列表 + 正文预览        │
│          │  · 设置：端口 / HUD / 世界文字      │
├──────────┴──────────────────────────────────┤
│ ▶/⏸  ⏮上一章  ⏭下一章   进度 42% · 朗读中 · 晓晓 │
└─────────────────────────────────────────────┘
```

### 2.1 状态条

- 左边绿点 + `已连接 <端口>`：WebSocket 已连上 App。
- 左边红点 + `未连接（App 未启动？）`：**面板照常可用**，只是拉不到数据。
- 右侧「重试」：重连 WebSocket 并重新拉一次书架 / 音色。App 刚启动时点它就行。

### 2.2 书架视图

- 列出 `/api/v1/books` 返回的书：标题 + 「作者 · N 章」。
- 点击一本书 → 自动切到「章节」视图并拉该书的章节列表。
- 顶部搜索框（回车或点「搜索」触发）走 `/api/v1/search`。
  - 搜索结果的条目是黄色的，点一下 = **加入书架**（`POST /api/v1/books`），随后自动刷新书架。
  - 如果 App 版本还没有搜索接口（HTTP 404），搜索框与按钮会**自动隐藏**，状态条提示「App 版本不支持搜索」，不会反复报错。

### 2.3 章节视图

- 上半区：章节列表（标题 + 字数）。
- 下半区：选中章节的正文预览（按面板宽度自动折行，超长自动截断）。
- 点击章节 = 同时做两件事：
  1. `POST /api/v1/reader/open` 打开该章 → `POST /api/v1/reader/play` 开始朗读；
  2. `GET /api/v1/books/{id}/chapters/{index}/content` 拉正文填预览。
- 点完看状态条：`正在朗读：第 X 章 …`。

### 2.4 设置视图

| 项 | 说明 |
| --- | --- |
| App 端口 | 1024~65535。点「保存并重连」写回配置并立刻重连（不需要重启游戏） |
| HUD 显示 | 开 / 关，等价于 J 键，会写回 `hud.showHud` |
| 世界文字 | 开 / 关，等价于 `hud.showWorldText` |
| HUD 位置 | 打开拖拽界面（等价于 H 键） |
| 只读信息 | 音色、重连间隔、心跳间隔、音频输出模式、当前 HUD 坐标 |

### 2.5 主菜单入口

主标题界面原版按钮列下方会多一个 **「阅读器」** 按钮（参考 DangoMusic 的做法），不进入世界也能打开面板查书、点章节开播。

### 2.6 焦点与输入法（重要）

- 面板打开时会**暂停单人游戏**（`isPauseScreen()` 返回 true），多人服里不会暂停服务器。
- 打开面板时 Minecraft 会释放鼠标指针，可以正常点击；关闭（ESC / 右上角没有关闭按钮时按 ESC）后恢复游戏。
- **中文输入法**：原版 `EditBox` 只接收键盘字符事件，微软拼音等输入法在上屏前的候选窗口不会显示。实测建议：
  - 搜索框里先输入英文 / 拼音再手选，或
  - 直接在 App 里搜索加书，游戏内只做「选书 → 选章 → 播放」。
  - 我们**没有**自绘 IME（原版 1.20.1 也没有），这是已知限制。
- EditBox 获得焦点后，`R`/`M` 等字母键会进入输入框而不是触发热键——这是预期行为（界面打开时热键全部不响应，且累积点击会被丢弃）。

---

## 3. 世界内 3D 悬浮文字

- 默认开启（`hud.showWorldText = true`），显示当前朗读句子。
- 位置：玩家视线前方约 3 格（`hud.worldTextDistance`，1.0~10.0 可调），略低于视线中心。
- 永远面向相机（用相机朝向旋转），文字超长按 **40 字符**截断。
- 只在游戏内且 `level/player` 都存在时绘制；App 未连接时什么都不画（不显示占位文字，避免刷屏）。
- 用的是 `Font.DisplayMode.SEE_THROUGH`，会穿透实体渲染，但在方块后面会被挡住（正常深度测试）。

---

## 4. HUD 与位置编辑

### 4.1 HUD 内容

| 状态 | 显示 |
| --- | --- |
| 已连接空闲 | `Reader Bridge: 已连接 · 空闲` |
| 未连接 | `Reader Bridge: App 未连接` + 尝试连接的 `ws://` 地址 |
| 朗读中 | 《书名》/ 章节 / 当前句 / `进度 42% · 已暂停 · 音色` |

### 4.2 拖动位置（H 键）

1. 按 **H** 打开「阅读 HUD 位置」界面（游戏内、主菜单都可以按）。
2. 按住蓝色方框拖动到想要的位置。
3. 松手即写回 `hud.hudX / hud.hudY` 并保存到磁盘（状态行显示「已保存：X=…, Y=…」）。
4. 「重置到左上角」恢复 (6, 6)；ESC 取消不保存。

> 为什么用界面而不是游戏内直接拖：游戏内鼠标被锁住没有指针，做不出可靠的拖拽。开界面会释放指针，这是稳定做法。

---

## 5. 音频（当前为预留）

- 模组监听 `tts.audio` 事件，**只有当 payload 里带 `wavPath`（16-bit PCM mono/stereo）时**才会用游戏内 OpenAL 播放。
- App 目前的 Edge TTS 只产出 **mp3**（payload 里是 `path`），模组不会尝试播放，也不报错——所以**默认状态下没有游戏内声音**，声音仍由 App 自己出。
- 等 App 侧补齐 PCM WAV 输出后，模组无需改动即可自动播放（会写入 OpenAL 的 buffer，并复用同一个 source）。
- 配置里预留了 `audio.outputMode = auto / java_sound / game`，当前只有 `game`（OpenAL）实现，`java_sound` 未实现。

---

## 6. 配置总览

`config/readerbridge-common.toml`（改动后多数项立即生效；`keys.*` 需重启）：

```toml
[bridge]
  enabled = true                 # 关掉就完全不连接
  wsPort = 47210                 # App 的 WebSocket/REST 端口
  reconnectIntervalMs = 3000     # 断线重连基础间隔（指数退避，上限 30s）
  logStateChanges = true         # 是否打印每次状态变化
  heartbeatIntervalMs = 15000    # 应用层心跳；连续 3 个周期无任何帧判定连接已死

[hud]
  showHud = true
  hudX = 6
  hudY = 6
  showWorldText = true
  worldTextDistance = 3.0

[keys]
  openPanel = "M"
  playPause = "G"
  nextChapter = "B"
  prevChapter = "N"
  toggleHud = "J"
  hudPosition = "H"

[audio]
  outputMode = "auto"            # 预留
```

---

## 7. 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| HUD 一直是「App 未连接」 | 先启动 App；确认 App 监听 47210；点面板里的「重试」 |
| 日志只有一条 `连接 ws://… 失败` | 正常设计：首次失败 WARN 一次，之后静默重试，连上会打 INFO |
| 书架是空的 | App 书架里没有书：用 App 添加，或在面板搜索框按键名搜索加入 |
| 搜索框不见了 | App 版本没有 `/api/v1/search`（404），模组自动隐藏搜索入口 |
| 点章节没声音 | 面板只负责「打开 + 开播」；声音由 App 播放。检查 App 是否在出声 |
| 按键没反应 | 改了 `keys.*` 没重启，或该键被其它模组占用（游戏内「控制」里改即可） |
| 改端口后连不上 | 设置界面会立刻重连；确认 App 侧端口一致后点「重试」 |
| 中文显示成方框 | 客户端语言资源问题；模组用原版 Unifont 回退渲染中文，正常情况下可直接显示 |

### 需要贴给开发者看日志时

游戏日志在 `.minecraft/logs/latest.log`（模组只输出 INFO 以上），DEBUG 级别的内容（例如每次状态推送）需要看 `run/logs/debug.log` 或在启动参数里加 `-Dforge.logging.mojid=DEBUG`。

搜这几个标记：

- `[readerbridge]` —— 模组自己的日志前缀
- `[MAIN-THREAD-OK]` / `[MAIN-THREAD-VIOLATION]` —— 测试附属模组验证回调线程的标记

---

## 8. 截图占位

> 待补：阅读面板书架视图、章节视图 + 正文预览、设置视图、3D 悬浮文字、HUD 位置编辑界面。
> 截图建议放在 `docs/screenshots/` 下，命名 `panel-books.png` / `panel-chapters.png` / `panel-settings.png` / `world-text.png` / `hud-position.png`。
