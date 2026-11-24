# 字幕位置 / 换句动效 / 语速 —— 预设系统

> 本文件是「世界内字幕外观」的唯一事实源。改 `WorldTextRenderer`、`LyricAnchor`、`TextFade`、
> `ReaderEngine.cycleSpeed` 之前先读这里；改完回来同步 §1 表格。

## 1. 配置项总表（`config/readerbridge-common.toml`）

全部可在 **Configured** 模组界面里改（Mods → ReaderBridge → 齿轮），改完即时生效，只有按键类要重启。

| 键 | 段 | 取值 | 默认 | 作用 |
|---|---|---|---|---|
| `showWorldText` | `hud` | true/false | `true` | 世界内 3D 字幕总开关 |
| `worldTextDistance` | `hud` | 3.0 ~ 48.0 | `10.5` | 字幕基准距离（格），与 MuoniumPlayer 一致 |
| `anchorPreset` | `hud` | `follow` / `stay` / `head` | `stay` | 字幕留在世界的哪种方式（§2） |
| `anchorSpreadDeg` | `hud` | 0 ~ 30（度） | `12` | `stay` 档每句横向随机散开的幅度；0 = 每句只变高度 |
| `subtitleFx` | `hud` | `crossfade` / `typewriter` / `typewriterBounce` / `wave` / `slideUp` / `glow` / `none` | `crossfade` | 换句时的文字动效（§3） |
| `subtitleFxMsPerChar` | `hud` | 10 ~ 400 | `45` | 打字机每字间隔（毫秒） |
| `subtitleFxAmplitude` | `hud` | 0.0 ~ 8.0 | `2.0` | 跳动/波浪/上滑幅度（渲染单位≈像素） |
| `worldTextFadeInMs` | `hud` | 0 ~ 3000 | `220` | 新句淡入时长，0 = 硬切 |
| `worldTextFadeOutMs` | `hud` | 0 ~ 3000 | `380` | 旧句淡出时长，0 = 立即消失 |
| `speedMode` | `behavior` | `direct` / `bake` | `direct` | 语速调节方式（§4） |
| `pauseOnGui` | `behavior` | true/false | `false` | 开背包/聊天等界面是否暂停 |
| `pauseOnLostFocus` | `behavior` | true/false | `true` | 离开世界或窗口失焦是否暂停 |

读取全部经过 `ReaderBridgeConfig` 的 `safeGet` 兜底：配置未加载或值非法时回落到默认，不会抛异常。
非法值策略写在各 getter 里（例如 `anchorPreset` 非法 → `follow`，`speedMode` 非法 → `direct`）。

## 2. 位置预设 `anchorPreset`

三档共用同一套绘制几何（折行、居中、`scale(-s,-s,s)`），只在「锚点怎么算」上分支。

| 档 | 名称 | 行为 | 观感 |
|---|---|---|---|
| `follow` | 字幕跟随（默认） | 锚点生成在世界坐标；玩家走动时整句平移到等距位置；转头飘出视野后由临界阻尼弹簧沿视线拉回 | 像挂在空中跟着你走，转头会追回来 |
| `stay` | 每句换位置（参考项目默认） | 句子一变就重新 spawn，横向随机 ±`anchorSpreadDeg`、纵向随机下沉 3~13°；句内仍跟随玩家位移、出视野由弹簧拉回 | 每句话落在不同位置，朗读有节奏感；同一句期间不会乱飘。**注意**：这里不是「所有句子钉在同一处」，那是 `follow` |
| `head` | 视线跟随 | 每帧钉在视野正前方固定距离，永远正对屏幕 | 像贴在镜头上，随视角转动而移动 |

- `follow` 的算法抄自 MuoniumPlayer `WorldLyricsState`：`keepDistance` 差分跟随、`STAY_SPRING=12.0`
  临界阻尼（子步 1/240s）、回屏死区 `0.5` 格、边距 `2°` 再内收 `4°`、越界上限 `distance*4.0+64.0`。
  常量与出处见 `docs/WORLDTEXT.md` §5。
- 换句**不重新 spawn**（决策 D-004）：阅读场景里每句跳位置不可接受，锚点只在首次 / 传送（>16 格）/ 越界时重算。
- 切档时重置锚点状态，避免两档状态互相污染。

## 3. 换句动效预设 `subtitleFx`

逐字效果都用 `Font.drawInBatch` 单字符一次调用（x 累加 `font.width(ch)`），整行居中仍按整行宽度算，
`endBatch()` 每帧只调一次。

| 档 | 名称 | 行为 |
|---|---|---|
| `crossfade` | 正常交叉淡化（默认） | 旧句按 `worldTextFadeOutMs` 淡出，新句按 `worldTextFadeInMs` 淡入，两句同时渐变 |
| `typewriter` | 打字机 | 逐字显现，每字间隔 `subtitleFxMsPerChar`，整句显示完后末尾有闪烁光标；旧句直接消失 |
| `typewriterBounce` | 打字机 + 跳动 | 同上节奏，每个字刚出现时向上弹一下再落回（`sin(π·p)·e^(-3p)`） |
| `wave` | 波浪 | 整句立刻全显，每字按 `sin(2πt/T + i·0.5)` 上下浮动，T≈2000ms |
| `slideUp` | 上滑 | 新句从下方滑入同时淡入，时长读 `worldTextFadeInMs`；旧句正常淡出 |
| `glow` | 文闪 | 整句立刻全显，逐字亮度在 0.55~1.0 之间脉动，相位按字符错开 |
| `none` | 硬切 | 无淡入淡出，直接替换 |

## 4. 语速 `speedMode`

| 档 | 行为 | 延迟 | 音调 |
|---|---|---|---|
| `direct`（默认） | 只改 OpenAL `AL_PITCH`，语速立刻变，不联网、不重新合成 | 0 | 会跟着变（1.5x 偏尖） |
| `bake` | 语速交给 Edge 合成（edge rate = `round((倍率-1)*10)`，clamp ±10），音调正确 | 需等当前块重新合成，一块约 1000 字 | 正常 |

- `direct` 下所有合成一律 `rate = 0`，一份 mp3 可跨语速复用。
- `bake` 下缓存 key 必须含 rate，否则不同语速会命中同一份音频、听起来没变。
- `bake` 切换时把 `AL_PITCH` 复位 1.0，否则音高与烘焙双重变调。
- 倍率换算 `edgeRate = round((倍率-1)*10)`，实测落点：`0.80x→-2`、`1.00x→0`、`1.25x→+3`、`1.50x→+5`、`2.00x→+10`。
  注意 **1.25x 是 +3**（`Math.round(2.5)` 进位），不是 +2；要 +2 需把 `ReaderEngine.edgeRateOfSpeed` 的 `Math.round` 换成 `Math.rint`。
- 缓存文件名只在 `rate != 0` 时把 rate 拼到 key 末尾，所以 `direct` 全档位与 `bake@1.0x` 都继续命中历史 `blk_*.mp3`。

## 5. 参考来源

| 来源 | 用来抄什么 |
|---|---|
| **MuoniumPlayer**（`.research/MuoniumPlayer`，已精读） | 锚点/跟随/回屏弹簧全部常量与公式；Skija 特效部分不抄 |
| **DangoMusic**（`.research/DangoMusic`，已 clone） | 只有功能清单可用（逐字歌词、3D 漂浮、发光/抖动/文闪）；**核心渲染不开源** |
| **Text Animator**（MC 模组，联网查证） | 动效命名与参数惯例：typewriter / bounce / wave / glow 的常见实现方式 |

### 5.1 DangoMusic 调研结论（重要，别再重复下载）

仓库 `Sorihiko/DangoMusic`（GPL-3.0，407 文件 / 3.6 MB）**只有各版本适配层**：
`modern/versions/1.20.1-forge/` 仅 `DangoMusicForge.java`(5.4K) + `PartyNetwork.java`，
`common/core/` 仅事件总线与 `FlacReader`。**3D 歌词渲染、逐字特效、播放器 UI 均不在公开仓库**
（闭源字节码分发）。

可用的两条实证：

1. `docs/shader-compatibility.md` 明确记录：**1.20.1 Forge 的歌词入口 = `RenderLevelStageEvent.Stage.AFTER_LEVEL`**，
   理由是「Forge 的 GameRenderer 补丁在 LevelRenderer 返回后派发」+「避开世界渲染期间的光影处理」。
   这与本项目 `WorldTextRenderer` 的选型一致，是 AFTER_LEVEL 决策的第二个独立佐证
   （第一个是 MuoniumPlayer `WorldLyricsBridge.java:91-97` 关于 Fabulous 半透明目标的说明）。
2. 同文档强调「不能仅凭 `LAST`、`AFTER_LEVEL` 这些事件名判断时机，要看对应版本 userdev JAR 的补丁」——
   我们已验证 Forge 47 的派发点，结论成立。

DangoMusic 的 `GameRendererMixin` / `GuiMixin` / `MusicManagerMixin` 属于 Fabric/NeoForge 侧接线，
对纯 Forge 的 `AFTER_LEVEL` 注册没有可抄内容。

## 6. 验收

进游戏（需要什锦铺 `18921` 在跑）→ 按 `M` 开面板 → 选书开读 → 世界中应出现字幕。逐项改
`config/readerbridge-common.toml`（或 Configured 界面）后立刻生效：

1. `anchorPreset` 三档：走开看是否跟随、转身看是否留在原地、转头看字是否始终在正前方。
2. `subtitleFx` 七档：每换一句观察显现方式（逐字/弹跳/波浪/上滑/闪烁）。
3. `speedMode`：`direct` 点语速应立刻变；`bake` 应出现"正在重新合成"并等 1~3 秒。
4. 任意值填成非法字符串 → 回落默认，不崩、不刷错误日志。

## 7. 未做 / 已知限制

- 不跟随 Minecraft 音量滑条（OpenAL 独立声源，`AL_GAIN` 恒 1.0）。
- `head` 档永不回屏（设计如此），极端 fov 下字幕可能超出屏幕边缘。
- 逐字效果在 3 行满行（约 42 字）+ 淡出旧句时最坏约 84 次 `drawInBatch`/帧，未实机测帧率；
  同一字形图集，理论只有 1 个 draw call，判断不需要降级。
- `typewriterBounce` 的位移峰值只有 `0.223 × amplitude`，默认 `amplitude=2.0` 时几乎看不出弹跳。
  想要明显弹跳把 `subtitleFxAmplitude` 调到 5 以上（上限 8）。
- `stay` 档每句都会换位置，句间是会跳的（这是该档定义）；要整段朗读停在同一个地方请用 `follow`。
- 本轮仅**编译验证**（0 error / 0 warning），七档动效与三档位置的**实机观感未经游戏内确认**。

## 8. 落地状态（2026-11-24 03:15）

- 源码：`client/WorldTextRenderer.java`（638 行，重写）+ `client/world/` 新增
  `AnchorMode` / `SubtitleFxMode` / `SubtitleAnimator` / `SubtitlePainter`；
  `ReaderEngine.java` 1259 行（`speedMode` 双模式 + `edgeRateOfSpeed` / `synthRate` / `playbackRateForMode`）。
- 配置：`ReaderBridgeConfig.java` 430 行，新增 5 个键（§1），全部带中文 `.comment()`，Configured 界面可直接生成。
- 产物：`readerbridge-1.0.0.jar` = **422,311 B / SHA256 前16 `31FD7A99AF1E7984` / 219 entries**，三处同源。
- 提交：`65a5333 feat(presets): anchor presets (follow/stay/head), 7 subtitle fx modes, configurable speed mode (direct/bake)`；
  备份 tag `backup_20261124_0315_presets`。
- 自测建议顺序：`stay + crossfade`（当前默认，每句换位置）→ `follow`（句间固定同一处）→ `head` → 其余六档动效。
