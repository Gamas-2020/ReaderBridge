# forge-bridge（readerbridge）

Minecraft **Forge 1.20.1** 桥接模组：连接本机 Reader App 的 WebSocket 服务
（`ws://127.0.0.1:47210/ws`），把阅读状态以**线程安全的方式**暴露给其它模组。

- modId：`readerbridge`
- 包名：`com.feiyu.readerbridge`
- Forge：`1.20.1-47.4.29`（ForgeGradle 6.0.54，Gradle 8.8，Java 17，`official` 映射）
- 运行时零第三方依赖：WS 用 JDK 17 内置 `java.net.http.WebSocket`，JSON 用 Minecraft 自带 Gson

## 构建

```powershell
$env:JAVA_HOME = "E:\toolchains\jdk-17"
cd "E:\肥鱼专用文件夹\novel_mod\forge-bridge"
.\gradlew.bat build --no-daemon "-Dorg.gradle.java.home=E:\toolchains\jdk-17"
```

产物：`build/libs/readerbridge-1.0.0.jar`

> 必须用 JDK 17 构建。系统默认 JDK 21 会让 ForgeGradle 6 失败。

## 运行验证（无 GUI 环境）

```powershell
# 1) 启动本地 mock WS 服务端（模拟 App 侧）
python ..\_tools\mock_ws_server.py 47210

# 2) 另开一个终端启动专用服务端
.\gradlew.bat runServer --no-daemon "-Dorg.gradle.java.home=E:\toolchains\jdk-17"
```

日志里应能看到：

```
[readerbridge] WebSocket 已连接: ws://127.0.0.1:47210/ws
[readerbridge] 收到 hello: service=reader-bridge-core, version=0.1.0
[readerbridge] 状态更新: ReadingStateSnapshot{...}
```

## 目录结构

```
src/main/java/com/feiyu/readerbridge/
  ReaderBridgeMod.java          @Mod 主类（构造期安装 API 单例）
  CommonModEvents.java          mod 总线：专用服务端启动、配置加载/重载、加载完成
  CommonForgeEvents.java        game 总线：服务端 tick flush、服务端停止关闭
  api/                          对外契约（ReadingState / ReaderBridgeApi / ReaderStateListener / ReaderBridgeProvider）
  impl/                         实现（快照、协议解析、WS 客户端、主线程派发、API 单例）
  config/ReaderBridgeConfig.java ForgeConfigSpec（COMMON）
  client/                       客户端专属（Dist.CLIENT 隔离）：
    ClientEvents.java             mod 总线：客户端初始化、HUD 覆盖层、热键注册
    ClientForgeEvents.java        game 总线：tick flush、热键处理、主菜单「阅读器」入口
    ClientKeys.java               5 个 KeyMapping（键名从配置读，改后需重启）
    ReaderBridgeHud.java          IGuiOverlay HUD
    WorldTextRenderer.java        RenderLevelStageEvent 世界内 3D 悬浮文字
    gui/ReaderBridgeScreen.java   侧栏式阅读面板（书架 / 章节 + 正文预览 / 设置）
    ui/SimpleListWidget.java      自绘滚动列表（不依赖 ObjectSelectionList）
    ui/HudPositionScreen.java     HUD 拖拽定位界面（H 键）
    http/ReaderApiClient.java     本地 REST 客户端（HttpClient.sendAsync，回调回主线程）
    audio/TtsAudioPlayer.java     OpenAL 播放 PCM WAV（仅当 payload 带 wavPath）
src/main/resources/
  META-INF/mods.toml            javafml，loaderVersion="[47,)"，displayTest="IGNORE_SERVER_VERSION"
  pack.mcmeta                   pack_format 15
  assets/readerbridge/lang/     zh_cn / en_us 按键文本
```

## 游戏内功能（客户端）

| 入口 | 说明 |
| --- | --- |
| **M** 键 / 主菜单「阅读器」按钮 | 打开侧栏式阅读面板：书架 → 章节 + 正文预览 → 点章节即「打开并播放」 |
| 面板状态条 | 已连接显示绿点 + 端口；未连接显示红字提示 + 「重试」按钮，面板照常可用 |
| **G / B / N** | 播放暂停 / 下一章 / 上一章（走 REST `POST /api/v1/reader/{action}`） |
| **J** | 切换 HUD |
| **H** | HUD 位置编辑：拖动方框，松手写回 `hud.hudX/hudY` |
| 世界内 | 玩家前方约 3 格显示当前朗读句子（`hud.showWorldText`，40 字符截断） |

详细按键、界面与故障排查见 [GUIDE.md](GUIDE.md)。


## 配置

`config/readerbridge-common.toml`：

```toml
[bridge]
  enabled = true
  wsPort = 47210
  reconnectIntervalMs = 3000
  heartbeatIntervalMs = 15000   # 应用层 ping 周期；连续 3 个周期无任何帧即判定死连接并重连
  logStateChanges = true

[hud]
  showHud = true
  hudX = 6
  hudY = 6
  showWorldText = true          # 世界内 3D 悬浮文字
  worldTextDistance = 3.0       # 1.0~10.0 格

[keys]                          # 改完需要重启游戏（Forge 不支持运行期重注册 KeyMapping）
  openPanel = "M"               # 打开阅读面板
  playPause = "G"
  nextChapter = "B"
  prevChapter = "N"
  toggleHud = "J"
  hudPosition = "H"             # 编辑 HUD 位置

[audio]
  outputMode = "auto"           # 预留：auto / java_sound / game（当前仅 game 有实现）
```

> `heartbeatIntervalMs` 的下限是 3000（判定死连接最长 = 3 × 该值）。
> 任意一帧（`state` / `event` / `pong` / `hello` / `error`）到达都会清零「未响应计数」。

## 与第三方模组的对接

见 [API.md](API.md)；协议细节见 [PROTOCOL.md](PROTOCOL.md)。

## 已踩过的坑（改代码前务必看）

1. **不要用 `DistExecutor.safeXXX` 做客户端派发。**
   Forge 的 `DistExecutor.validateSafeReferent` 通过反射读取 lambda 的 `SerializedLambda`，
   比较 `getCapturingClass()` 与 `getImplClass()`，相同就抛
   `RuntimeException: Unsafe Referent usage found in safe referent method`（客户端 FATAL，所有回调丢失）。
   本项目统一用 `DistExecutor.unsafeCallWhenOn(Dist.CLIENT, ...)`，并在前面加
   `FMLEnvironment.dist == Dist.CLIENT` 守卫，保证专用服务端不会执行 lambda、也不会链接客户端类。

2. **配置事件会重复触发。**
   Forge 在首次 `ModConfigEvent.Loading` 之后会紧接着再派发 `ModConfigEvent.Reloading`，
   若每次 `Reloading` 都重建连接，会与启动期 `start()` 竞争，旧线程池被提前关闭 →
   日志出现一次 `RejectedExecutionException`。`ReaderBridgeApiImpl` 用
   `appliedSignature = enabled|wsPort|reconnectIntervalMs` 做去重，只在参数真的变化时重连。

3. **jar 内 `mods.toml` 的编码。**
   `processResources` 必须写 `filteringCharset = 'UTF-8'`，否则 Windows 平台默认 GBK，
   打包进 jar 的 `mods.toml` 中文会乱码（Forge 按 UTF-8 读）。

4. **看日志要按 GBK 解码。**
   dev 环境 JVM 未设置 `-Dfile.encoding=UTF-8`（Java 17 on Windows → GBK），
   `run/logs/latest.log` 是 GBK；用 UTF-8 打开会看到乱码，不是模组输出错误。

5. **DEBUG 级日志不在 `latest.log` 里。**
   Forge dev 把 INFO 及以上写 `run/logs/latest.log`，DEBUG 单独写 `run/logs/debug.log`。
   查心跳 ping/pong（`心跳 ping 发送` / `收到 pong`）、`tts.audio` 事件时必须看 `debug.log`；
   `latest.log` 只有 INFO 汇总（`心跳统计：累计发送 ping N 次`）与状态更新。

6. **心跳与死连接判定的实测方式。**
   用 `_tools/silent_ws_server.py <port>` 起一个握手后完全沉默的服务端，
   把 `wsPort` 指过去并把 `heartbeatIntervalMs` 调到 3000，约 9 秒后应看到
   `WARN ... 连续 3 个心跳周期未收到任何帧，判定连接已死，主动断开并重连`，随后自动重连。

