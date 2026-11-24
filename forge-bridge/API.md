# Reader Bridge 对外 API（forge-bridge）

包名：`com.feiyu.readerbridge.api`
唯一入口：`com.feiyu.readerbridge.api.ReaderBridgeProvider`

第三方模组引用方式：在 `build.gradle` 依赖 `forge-bridge` 的编译产物（发布后的
`readerbridge-1.0.0.jar`，或开发期的 `forge-bridge/build/classes/java/main` 目录）。
API 包内**只有**四个 public 类型，不含任何内部实现类。

---

## 1. `ReaderBridgeProvider`（入口）

```java
public final class ReaderBridgeProvider {
    /** 未初始化时抛 IllegalStateException */
    public static ReaderBridgeApi get();
    /** 未初始化时返回 Optional.empty() */
    public static Optional<ReaderBridgeApi> getIfPresent();
}
```

- 模组构造期即完成安装，因此在 `FMLCommonSetupEvent` 及之后一定可用。
- **推荐**在加载期用 `getIfPresent()`，避免依赖加载顺序。

## 2. `ReaderBridgeApi`

```java
public interface ReaderBridgeApi {
    ReadingState getState();                        // 永不返回 null
    void addListener(ReaderStateListener listener);  // 幂等（addIfAbsent）
    void removeListener(ReaderStateListener listener);
    void requestPause();                             // command: pause
    void requestResume();                            // command: resume
    void requestPlay();                              // command: play
    void requestStop();                              // command: stop
    void requestNext();                              // command: next
    void requestPrev();                              // command: prev
    boolean isConnected();
    String wsUrl();                                  // 当前/期望连接的地址，便于诊断
}
```

- `getState()` 返回不可变快照，**任何线程**都可调用；未连接时返回 inactive 快照
  （`isActive()==false`，文本字段为 `null`，`progress()==0.0`，
  `lastUpdateEpochMs()==0`）。
- `requestPause()` / `requestResume()` / `requestPlay()` / `requestStop()` /
  `requestNext()` / `requestPrev()` 都是「请求」，发送一帧
  `{"type":"command","action":"<动作>"}`，实际是否执行由 App 决定；
  未连接时静默忽略（仅 debug 日志）。
- `requestResume()` 与 `requestPlay()` 的差别来自协议：`resume` 只对已暂停的会话生效，
  `play` 是「开始播放」语义。

## 3. `ReadingState`

```java
public interface ReadingState {
    boolean isActive();
    String bookTitle();        // 可能为 null
    String chapterTitle();     // 可能为 null
    String currentSentence();  // 可能为 null
    double progress();         // 0.0~1.0（越界会被裁剪）
    boolean isPaused();
    String ttsVoiceId();       // 可能为 null
    long lastUpdateEpochMs();  // 0 表示从未收到状态
}
```

协议里对应的字段名是 `sentence`，API 里叫 `currentSentence()`（语义相同）。

## 4. `ReaderStateListener`

```java
@FunctionalInterface
public interface ReaderStateListener {
    void onReadingStateChanged(ReadingState newState);
}
```

### 线程模型（硬性约定）

| 调用 | 允许的线程 | 说明 |
| --- | --- | --- |
| `getState()` | 任意线程 | 读 volatile 不可变快照 |
| `addListener` / `removeListener` | 任意线程 | 线程安全列表 |
| `requestPause` / `requestResume` / `requestPlay` / `requestStop` / `requestNext` / `requestPrev` | 任意线程 | 内部异步发送 |
| `onReadingStateChanged` | **只在主线程** | 由桥接派发 |

派发实现：

1. 客户端：`Minecraft.getInstance().execute(...)`（已在主线程则直接执行），
   客户端类通过 `DistExecutor.unsafeCallWhenOn` + `FMLEnvironment.dist == Dist.CLIENT` 守卫间接加载，
   专用服务端不会碰到 `net.minecraft.client.*`（不能用 `safeCallWhenOn`：Forge 的
   `validateSafeReferent` 会把本实现的嵌套 lambda 判为 unsafe 并抛异常）。
2. 专用服务端：`ServerLifecycleHooks.getCurrentServer().execute(...)`（同线程直接执行）。
3. 两者都不可用（如 `FMLCommonSetupEvent` 早期）：任务入队，等主线程 tick /
   `FMLLoadCompleteEvent` 时 flush，顺序保持 FIFO。

每个监听器回调独立 try/catch：一个 listener 抛异常只记录日志，不影响其它 listener，
也不会断开 WebSocket。

## 5. 生命周期与配置

- 配置：`config/readerbridge-common.toml`（`ModConfig.Type.COMMON`）
  - `bridge.enabled`（默认 true）
  - `bridge.wsPort`（默认 47210，范围 1024..65535）
  - `bridge.reconnectIntervalMs`（默认 3000，范围 500..60000，指数退避、上限 30s）
  - `bridge.heartbeatIntervalMs`（默认 15000，范围 3000..600000；应用层 ping 周期，
    连续 3 个周期无任何帧到达即判定连接已死并重连；任意帧到达都会重置未响应计数）
  - `bridge.logStateChanges`（默认 true）
  - `hud.showHud` / `hud.hudX` / `hud.hudY`
- 配置重载（`ModConfigEvent.Reloading`）会自动重建连接；`enabled` / `wsPort` /
  `reconnectIntervalMs` / `heartbeatIntervalMs` 任一变化才真正重建（避免启动期重复连接）。
- 服务端停止（`ServerStoppedEvent`）、模组卸载会关闭 WS 与线程池。


## 六、增量 API（Java 8+ default 方法，向后兼容）

两个方法以 default 形式加入 ReaderBridgeApi，**属增量扩展、不构成破坏性变更**：第三方实现即使不覆写也能编译运行（拿到兜底值），已发布契约不 breaking。

`java
/** 自上次连接成功以来累计的连接尝试次数；从未成功也计数。UI 展示用。 */
default int connectionAttempts() { return 0; }

/** 运行时修改重连间隔（毫秒），内部 clamp 后写回配置并立即重建连接。 */
default void setReconnectIntervalMs(long ms) { }
`

| 方法 | 线程 | 说明 |
|---|---|---|
| connectionAttempts() | 任意线程 | 每次发起连接尝试 +1，连接成功后清零；实现取自 WebSocket 客户端的独立原子计数 |
| setReconnectIntervalMs(long) | 任意线程 | clamp 到 [500, 60000]（受既有 defineInRange 上界约束），写 
eaderbridge-common.toml 后经 
estart() 的签名比对生效；同值不打断现有连接 |
