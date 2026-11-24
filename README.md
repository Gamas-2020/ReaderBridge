# ReaderBridge

Minecraft **Forge 1.20.1** 的阅读投射模组：在游戏内搜索、阅读、听书，并把正在朗读的句子投射到世界空间。

模组自包含 —— 书源解析、TTS 合成、音频播放、阅读界面全部在游戏进程内完成，**不依赖任何外部程序**。

| | |
|---|---|
| modId | `readerbridge` |
| 版本 | `0.6.2-alpha` |
| Minecraft | 1.20.1 |
| Forge | 47.4.29+ |
| Java | 17 |
| 许可证 | MIT |
| 实现 | **Little Code Sauce**（AI 智能体，全部代码由其编写） |
| 产品与验收 | **feiyu** |

> **实现声明**：本仓库的全部程序代码与文档，均由 AI 智能体 **Little Code Sauce** 自主编写、构建、调试与验证，并非由人类逐行手写。人类的贡献在于提出需求、确认设计方向、在真实环境中复现问题并验收结果。详见[作者与贡献者](#作者与贡献者)。

## 功能

| 模块 | 说明 |
|---|---|
| 阅读面板 | 五视图（书架 / 搜索 / 目录 / 正文 / 设置），按 `M` 打开；装了晴雪UI 用其 ore 主题，未装自动回退原版界面 |
| 书源 | 内嵌规则书源引擎（CSS 选择器规则），可导入 `.json` 书源；支持什锦铺本机接口；支持拖拽导入本地 TXT |
| 朗读 | Edge TTS 在线合成，模组内 Java 原生 WebSocket 实现，零外部依赖；块级合成 + 句级时间轴 |
| 播放 | OpenAL 直出 PCM，支持即时变速、音量、水下低通滤波 |
| 世界文字 | 正在朗读的句子以 3D 悬浮文字投射到世界空间，三档锚点预设 + 七种字幕动效 |
| 续听 | 书架记录每本书的章节与句位置（`config/readerbridge/library.json`），重新进入世界即可续听 |
| 模组 API | 向其它模组暴露只读阅读状态接口，回调保证在 Minecraft 主线程 |

## 构建

### 前置

`forge-bridge/libs/ApricityUI-forge-1.20.1-1.3.1.jar` 是**编译期必需**的 `compileOnly` 依赖（WebUI 渲染层）。因许可证为 LGPL-2.1 且体积较大，**未纳入版本库**，需自行下载后放入该目录：

- 在 Modrinth / CurseForge 搜索 `ApricityUI`，选 `1.20.1` 的 Forge 版 `1.3.1`
- 该 jar **不会**打进产物；玩家未安装晴雪UI 时模组自动回退原版界面

`forge-bridge/libs/jlayer-1.0.1.jar`（LGPL-2.1，MP3 解码）**已随仓库提供**，构建时会被 embed 进产物。

### 命令

```bash
cd forge-bridge
gradlew.bat build      # Windows
./gradlew build        # Linux / macOS
```

产物：`forge-bridge/build/libs/readerbridge-<version>.jar`

最小测试附属模组（演示第三方模组如何接入本模组的 Java API，玩家无需安装）：

```bash
cd forge-test-addon
gradlew.bat build
```

## 安装

1. 把 `readerbridge-<version>.jar` 放进 `.minecraft/mods/`
2. 可选：同时安装晴雪UI（ApricityUI）以获得新一代面板观感
3. 启动游戏

纯客户端可用；服务端装不装都能进服（`displayTest="IGNORE_SERVER_VERSION"`）。

## 热键

| 键 | 功能 |
|---|---|
| `M` | 打开 / 关闭阅读面板 |
| `G` | 播放 / 暂停 |
| `B` | 下一章 |
| `N` | 上一章 |
| `J` | 3D 世界文字开关 |
| `H` | 拖动 HUD 位置 |

## 配置

`config/readerbridge-common.toml`。面板「设置」页可直接改其中大部分项：

- 书源：`builtinSources`
- 网络：`wsPort`、`reconnectIntervalMs`、`heartbeatIntervalMs`
- 世界文字：`showWorldText`、`worldTextDistance`、`anchorPreset`（`stay` / `follow` / `head`）、`anchorSpreadDeg`、`subtitleFx` 及其速度与幅度、`worldTextFadeInMs` / `worldTextFadeOutMs`
- 音频：`ttsVolume`、`speedMode`（`direct` / `bake`）
- 界面：`uiMode`、`hudMode`、`hudScale`、`showHud`
- 行为：`pauseOnGui`、`pauseOnLostFocus`、`logStateChanges`

## 给其它模组用的 API

完整说明见 [docs/API.md](docs/API.md)。核心入口：

```java
ReadingState state = ReaderBridgeProvider.get().getState();

ReaderBridgeProvider.get().addListener(newState -> {
    // 回调始终在 Minecraft 主线程执行，可直接操作游戏对象
    System.out.println(newState.bookTitle() + " / " + newState.currentSentence());
});
```

## 文档

- [docs/API.md](docs/API.md) — 模组间 Java API 与线程模型
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — WebSocket 消息协议
- [docs/AUI-UI.md](docs/AUI-UI.md) — 晴雪UI 面板结构与数据通道
- [docs/WORLDTEXT.md](docs/WORLDTEXT.md) — 世界内 3D 文字设计
- [docs/PRESETS.md](docs/PRESETS.md) — 锚点与动效预设
- [docs/SOURCES.md](docs/SOURCES.md) — 书源规则格式

## 作者与贡献者

| 角色 | 署名 | 职责 |
|---|---|---|
| **实现** | **Little Code Sauce**（AI 智能体） | 本仓库全部程序代码与文档：Java 模组本体、Edge TTS 客户端、OpenAL 音频管线、世界内 3D 文字渲染、晴雪UI 面板与数据通道、最小测试附属模组、全部 docs；以及构建、崩溃取证、缺陷修复与实机验证 |
| **产品与验收** | **feiyu** | 需求定义、功能取舍与优先级、真实环境下的问题复现与验收、发布决策 |

**实现声明**：本项目的程序代码完全由 AI 智能体 **Little Code Sauce** 编写。人类的贡献在于提出需求、确认设计方向、在真实环境中复现问题并验收结果 —— 而非逐行手写代码。

代码由 AI 生成，但每一个缺陷都在真实环境中定位并修复：例如 Java 模块系统因依赖平铺而抛 `ResolutionException` 导致游戏静默秒退、OpenAL 声源默认落在世界原点导致完全无声、晴雪UI 的 `WeakHashMap` 并发修改导致渲染线程崩溃、输入框被程序回填导致光标失步等，全部经由日志取证 → 复现 → 修复 → 实机验证闭环处理。

## 许可证

本项目代码以 MIT 许可发布，见 [LICENSE](LICENSE)。第三方组件与合规措施见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

内置样书均为公有领域文本；内置规则书源仅指向公开可访问的古籍站点。
