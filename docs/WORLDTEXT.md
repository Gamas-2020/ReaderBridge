# 世界内 3D 文字（世界歌词）· 实现事实源

> **本文件是 `readerbridge` 世界内文字渲染的唯一事实源。**
> 改 `WorldTextRenderer` / 事件注册 / 渲染阶段之前先读本文件；改完必须同步 §6「当前代码状态」与 §8「决策记录」。
> 最后核对：2026-11-24（读 `MuoniumPlayer\1.20.1 recode` 世界歌词源码，逐行；行号以该树为准）

---

## §1 事件接入：用 `AFTER_LEVEL`，不要改到别的 stage

`WorldLyricsBridge.java:99-226` 是参考实现的世界渲染结束入口。要点：

| 事实 | 出处 |
|---|---|
| 入口方法 `worldRenderEnd()`；1.20.1 里设计调用者是 mixin 注入 `LevelRenderer.renderLevel` 的 `RETURN` | `WorldLyricsBridge.java:88-99` |
| **Forge 47 的 fmlloader 不读 `mods.toml` 的 `[[mixins]]`（那是 NeoForge 语法）→ mixin 在 Forge 侧是死代码** | `:105-109` |
| 因此**加载器事件（`RenderLevelStageEvent`）必须能独立把文字画出来**；mixin 与事件两处同时到达靠守卫去重，否则加色混合叠两遍（同一帧重复画全部顶点 → 叠成白团） | `:104-113` |
| 去重实现：`if (now - lastRenderEndNanos < 1_000_000L) return;`（1ms 时间戳窗口） | `:110-114` |
| 空场景（`mc.player == null || mc.level == null`）显式 `hudCameraValid = false` 后 return —— 不让后续阶段拿上一帧陈旧投影画到加载屏上 | `:120-129` |
| 渲染异常 → `broken = true` 整体停用（重启恢复）；**HUD 路径刻意不设 broken**，只损失本帧 | `:222-225`、`:310-316` |

**结论**：`AFTER_LEVEL` 是参考实现明确选定的阶段，理由写在 `:91-97`（Fabric 的 `WorldRenderEvents.LAST` 在 Fabulous 半透明目标合成回主目标之前，水/玻璃会盖住文字）。**"AFTER_LEVEL 里深度写不进主帧"不是需要规避的问题** —— 参考实现在这一步直接关掉深度测试（§3），不依赖深度。

> ⚠️ 我上一轮把 stage 从 `AFTER_LEVEL` 改成 `AFTER_TRANSLUCENT_BLOCKS` 是**错误决策**，理由见 §8 决策 D-002。

---

## §2 相机姿态与矩阵：三步，顺序不可换

`WorldLyricsBridge.java:178-221`：

```java
// 1. 相机姿态自己算（不要用加载器/事件给的矩阵）
Matrix4f basePose = new Matrix4f()
        .rotateX((float) Math.toRadians(camera.getXRot()))
        .rotateY((float) Math.toRadians(camera.getYRot() + 180.0));

// 2. 视图矩阵临时换单位矩阵（1.20.1 的 getModelViewStack() 返回 PoseStack，
//    不是 1.21.1 的 Matrix4fStack：pushPose 后 setIdentity 等价）
PoseStack viewStack = RenderSystem.getModelViewStack();
viewStack.pushPose();
viewStack.setIdentity();
RenderSystem.applyModelViewMatrix();

// 3. ...设 GL 状态、画、还原...
viewStack.popPose();
RenderSystem.applyModelViewMatrix();
```

为什么自己算：`:28-30` ——「Fabric 和加载器给的矩阵不一致，Forge 那边歌词会左右反过来；自算两边结果一致，与原版画实体用的完全相同」。

**关键推论（决定了文字怎么落地）**：把 modelview 设成单位阵、再用相机姿态矩阵当根 pose，等于让整个绘制工作在**相机空间**（x 右 / y 上 / z 轴向前方，符号待实测）。因此：
- 文字平面**天然面向相机**，不需要额外做 billboard 反向旋转（原版 `Font.drawInBatch` 拿这个矩阵直接画就是正对镜头的）
- 锚点坐标就是这个相机空间里的 (x, y, z)，`distance` 语义即"沿视线方向多远处"

pose 的挂载方式（`:453-464`）：新建一个 `PoseStack`，`pushPose()` 后 `poseStack.last().pose().set(basePose)` 覆盖根矩阵，再交给文字批次器 `begin(poseStack, bufferSource, eyeX, eyeY, eyeZ)`。

---

## §3 GL 状态：进入设 6 项、退出**还原成原版默认**

进入（`:194-200`）：

| 项 | 值 |
|---|---|
| blend | `true` |
| blendFuncSeparate | `SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO` |
| depthTest | **`false`** |
| depthMask | **`false`** |
| cull | `false` |
| activeTexture | `GL_TEXTURE0` |

退出（`:203-221`）：`depthTest(true)`、`depthMask(true)`、`cull(true)`、`blend(false)`、`blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO)`。

**为什么还原成"默认"而不是"进入时的值"**（`:204-211` 原文）：`AFTER_LEVEL` 之后的原版绘制（GUI 等）期望的是默认态；blend 与 activeTexture 只设不恢复会泄漏 —— early-return 帧（无歌词）后面就是原版 GUI，混合态残留会让无 transparency shard 的 RenderType 继承环境 blend。

**结论**：我们侧必须逐项还原，且 `depthMask(false)` 的恢复要放在渲染结束后无条件执行（异常也要走 finally）。

---

## §4 bob 防抖（可选，可省略但会晃）

`:336-405`：1.20.1 的投影 P 已乘上 `bobHurt/bobView`，走路时视角摇晃会连带文字顶点一起晃。做法：绘制窗口内把投影临时换成**纯 fov 透视**：

```java
float partialTick = mc.getFrameTime();
double fov = (Double) getFov.invoke(gameRenderer, camera, partialTick, true); // getFov 是 private → 反射
Matrix4f pure = gameRenderer.getProjectionMatrix(fov);
RenderSystem.setProjectionMatrix(new Matrix4f(pure), VertexSorting.DISTANCE_TO_ORIGIN);
// ...画完...
RenderSystem.setProjectionMatrix(new Matrix4f(backup), VertexSorting.DISTANCE_TO_ORIGIN);
```

| 事实 | 出处 |
|---|---|
| `getFov` 是 private，需反射；生产环境分别是 `getFov` / `method_3196` / `m_109141_`（按映射命名空间） | `:341-351` |
| 一次性熔断：反射失败或投影异常 → `bobFreeProjFailed = true`，不再重试，日志「bob 防抖不可用（歌词将随视角摇晃）」 | `:353-393` |
| 省略它的后果：走动/受伤时文字随视角一起抖 | `:325-334` |

我们侧：**第一版可省略**（先保证可见），列入 §9 待办。

---

## §5 锚点与坐标系（待子代理补充精确算法）

已从源码确认的常量（`WorldLyricsSettings.java` / `WorldLyricsState.java`）：

| 常量 | 值 | 出处 |
|---|---|---|
| `distance` 默认 | **10.5** 格 | `WorldLyricsSettings.java:33` |
| `distance` clamp | **3.0 ~ 48.0** | `WorldLyricsSettings.java:89` |
| `keepDistance` 默认 | `true` | `WorldLyricsSettings.java:64` |
| 世界缩放 | `PX_TO_WORLD * scale * (distance / 6.5)` | `WorldLyricsState.java:1175` |
| `keepAway` 最小距离比 | `distance * 0.6` | `:61`、`:1862-1865` |
| 锚点越界上限 | `distance * 4.0 + 64.0` | `:800-802` |
| 回屏死区 | 0.5 格 | `:1126-1129` |
| 跟随差分 | `followEyeX/Y/Z`，`Double.NaN` = 失效重置 | `:989`、`:1035-1075` |
| fading 行也跟随 | `active` 与 `fading` 同序处理 | `:805-809`、`:1179-1180` |

**锚点语义（已按 `WorldLyricsState.java` 逐行核实）**：从 `followAnchor` / `applyAnchorFollow` 的存在可确认 —— 锚点是**世界坐标固定**的，不是每帧贴在视线正前方；玩家位移时整体平移（`keepDistance`），转头导致句子出视野时用**回屏弹簧**沿视线方向拉回设定距离（`stayOnScreen`）。这与"固定在视线正前方 3 格"是两种不同方案，后者（我原实现）没有回屏与跟随，转头文字就消失。

---

## §6 当前代码状态（**源码与已部署 jar 不一致**）

| 位置 | 状态 |
|---|---|
| `forge-bridge\src\main\java\com\feiyu\readerbridge\client\WorldTextRenderer.java` | 已被我改成 `RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS` + 自定义深度状态收尾。**偏离参考实现（§1）**，且**未经编译验证** |
| 已部署 jar `readerbridge-1.0.0.jar`（`111,588 B`，SHA256 前缀 `EE8D268786E8FCA0`） | 其中 `WorldTextRenderer` 是**改动前**的 `AFTER_LEVEL` 版本 |
| 结论 | **已落地**：§1/§2/§3 全部写全、编译通过、已部署，见 §6.1 |

### §6.1 落地状态（2026-11-24 01:13）

| 项 | 事实 |
|---|---|
| `client\WorldTextRenderer.java` | 重写为 `AFTER_LEVEL` + 自算相机姿态 + modelview 置单位 + GL 六项进出 + 1ms 幂等守卫；`Font.drawInBatch(SEE_THROUGH, FULL_BRIGHT)` 硬阴影，多行居中折行 |
| `client\world\LyricAnchor.java`（新增） | 锚点状态机：`spawn` / `follow` / `clampIntoView`（临界阻尼弹簧 ω=12、子步 1/240s、只改球坐标两角保半径）+ `isSane`；每个常量都标注了 `State.java` 出处行号 |
| 编译 | `gradlew.bat build --offline` → `BUILD SUCCESSFUL in 14s`，无 error 无 warning |
| jar | `readerbridge-1.0.0.jar` = **120,035 B**，SHA256 `5DF3FED8B2C3A531B51586662C84A8711B7EBE23FA635A8AB5111B03A2B9FB5E`，74 entries / 53 classes，含 `WorldTextRenderer.class`、`LyricAnchor.class`、`LyricAnchor$View.class` |
| 部署 | `E:\MC\versions\What Fucking This Game\mods\readerbridge-1.0.0.jar` 与桌面副本，字节一致 |
| 观感差异（已知取舍） | 无 Skija 高斯辉光 / 逐字崩解 / orbit+breath 呼吸动画；字面为原版 Font 硬阴影 |
| **未验证** | ① 游戏内实机视觉（需启动客户端进世界观察）；② z 轴手性与左右镜像（§7 第 5 点，代码里用 `scale(-s,-s,s)` 双负号，理论正确但未目视确认） |

### §6.2 与参考实现的刻意偏离

| 偏离点 | 参考实现 | 我们 | 理由 |
|---|---|---|---|
| 句子切换 | 每句都 `spawn` 并随机散开到 ±`spreadLimit` | 只首次/传送/越界 spawn，换句仅换文本 | 歌词多行错落是特性；阅读要位置稳定（D-004） |
| `spreadDeg` | 按配置随机散开 | 固定传 `0`（正中） | 只显示一句，散开无意义（D-005） |
| `aboveGround` | 支持贴地约束 | 不支持 | 文字关深度测试恒在最上层，不会真的「陷进地里」 |
| bob 防抖 | 反射 `GameRenderer.getFov` 替换投影 | 不做 | 走动/受伤时文字随视角轻微摇晃；第一版跳过（§4） |

---

## §7 原版 Font 替代 Skija 的落地要点

参考实现用 Skija + 自建 glyph 图集（`LyricGlyphAtlas`，`WorldLyricsBridge.java:65-74`）与 `SkijaHost` 的 ARGB 常量；我们**不引 Skija**，对应关系：

| 参考实现 | 我们侧 |
|---|---|
| Skija glyph 图集 + `smoothCharWidth` | 原版 `Font.drawInBatch(...)`（`MultiBufferSource` 批次提交） |
| 自建矩阵栈 `Fixed.reset(basePose)` | `new PoseStack()` + `last().pose().set(basePose)`（§2） |
| 加色辉光 / 逐字崩解 / orbit+breath 动画 | **不实现**（Skija 特有，见 §9） |
| `WorldBillboard.pushBillboard/popBillboard` | 每段文字自行 `poseStack.pushPose()/translate()/popPose()` |
| `WorldBillboard.end()` | 需要显式提交批次：渲染末尾 `bufferSource.endBatch()`（否则顶点不落地） |

要点：
1. 亮度用全亮（`LightTexture.FULL_BRIGHT` = `0xF000F0`），避免夜间世界文字发黑
2. 关深度写入 + 无深度测试（§3）→ 文字不会被方块遮挡；这是参考实现的有意选择
3. 多行居中：按 `font.width(line)` 算半宽做 `translate(-w/2, 0, 0)`，行高 `font.lineHeight + 间距`
4. 不额外做 billboard 旋转（§2 推论：坐标系已是相机空间）
5. z 轴正负与手性必须**实测一次**确认（否则文字会跑到脑后或被左右镜像）

---

## §8 决策记录

| ID | 日期 | 决策 | 理由 |
|---|---|---|---|
| D-001 | 2026-11-24 | 世界文字照抄 MuoniumPlayer，不自创 | 用户要求"大部分照抄"；成熟项目已验证事件/矩阵/GL 全套 |
| D-002 | 2026-11-24 | **撤销**"改用 `AFTER_TRANSLUCENT_BLOCKS`" | 依据 `WorldLyricsBridge.java:91-97` + `:194-200`：参考实现在 `AFTER_LEVEL` 关掉深度测试即可，不存在必须换 stage 的问题。原决策源于我自己臆测（"深度写不进主帧"），未核对已读源码 |
| D-003 | 2026-11-24 | 本文件作为唯一事实源，改代码前必读 | 反复靠临时记忆导致自我推翻（D-002） |
| D-004 | 2026-11-24 | 句子变化**不重新 spawn** 锚点 | 参考项目是歌词，每句随机散落是新位置；阅读场景同章内每句跳位置不可接受。加 `anchored` 标志，只在首次 / 传送 / 越界时 spawn |
| D-005 | 2026-11-24 | `spreadDeg` 固定传 **0**；spawn 时 pitch 下沉 3~13° | 只显示一句，横向散开无意义且会把字推到视野边缘；下沉让字略低于视线中心，不挡视野 |
| D-006 | 2026-11-24 | 位置**世界坐标固定**，不做「粘在视线正前方」 | 粘贴式实现转头文字即消失且完全无跟随感；参考实现是「世界锚点 + 位移整体平移 + 出视野弹簧拉回」（§5） |

---

## §9 待确认 / 待办

1. §5 锚点算法（`applyAnchorFollow` / `followAnchor` / 回屏弹簧 / 首次锚点生成）—— 子代理 `worldlyrics-read` 正在读，产出 `.reports/worldlyrics-spec.md`
2. 相机空间 z 轴符号与手性实测（决定文字朝前还是朝后）
3. bob 防抖是否第一版就做（建议先不做，列入后续）
4. 视觉效果范围：淡入淡出、fading 行保留时长、描边、辉光 —— 逐项决定抄/砍
5. 编译验证 + 部署 jar 后与源码重新对齐（§6）
