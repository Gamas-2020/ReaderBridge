# Reader Bridge 对外 Java API

**适用版本**：bridge 1.0.0 · Minecraft 1.20.1 · Forge 47.4.x · Java 17

本文档面向**其他 Forge 模组的开发者**。你不需要了解 WebSocket、JSON 或阅读器 App 的任何细节，
只需依赖 bridge 的 jar，就能读到"玩家当前在读什么、读到哪一句"，或请求 App 暂停/继续。

---

## 1. 快速上手（30 秒版）

```java
// ① 取 API（加载期之后一定可用，永不为 null）
ReaderBridgeApi api = ReaderBridgeProvider.get();

// ② 注册监听器（回调保证在 Minecraft 主线程）
api.addListener(state -> {
    if (!state.isActive()) return;            // 没在阅读
    System.out.println(state.currentSentence());
});

// ③ 需要时主动查询（任意线程安全）
String book = api.getState().bookTitle();
```

---

## 2. 包结构

包名：`com.feiyu.readerbridge.api`

对外只有 **4 个 public 类型**，**不含任何内部实现类**：

| 类型 | 作用 |
|---|---|
| `ReaderBridgeProvider` | 唯一入口，取 `ReaderBridgeApi` 实例 |
| `ReaderBridgeApi` | 状态读取 + 监听器管理 + 控制请求 |
| `ReadingState` | 不可变状态快照 |
| `ReaderStateListener` | 状态变化回调（函数式接口） |

> 设计上刻意不依赖 Forge 的 Capability 系统：Capability 天然附着于
> `BlockEntity` / `Entity` / `ItemStack`，而"全局单例服务"不是它的适用场景。
> 用静态服务定位器对第三方模组更简单，也避免了注册顺序依赖。

---

## 3. API 参考

### 3.1 `ReaderBridgeProvider`

```java
public final class ReaderBridgeProvider {
    /** 取 API 实例。bridge 模组构造期即完成安装，因此 FMLCommonSetupEvent 及之后必定可用。
     *  若确实未安装，抛 IllegalStateException。 */
    public static ReaderBridgeApi get();

    /** 同上，但未安装时返回 Optional.empty()。**推荐在加载期使用**，避免依赖加载顺序。 */
    public static Optional<ReaderBridgeApi> getIfPresent();
}
```

### 3.2 `ReaderBridgeApi`

```java
public interface ReaderBridgeApi {
    /** 当前状态快照，永不返回 null。未连接时返回 inactive 快照。可任意线程调用。 */
    ReadingState getState();

    /** 注册监听器。幂等：同一实例重复注册只保留一份。可任意线程调用。 */
    void addListener(ReaderStateListener listener);

    /** 注销监听器。对未注册的实例调用是安全的空操作。 */
    void removeListener(ReaderStateListener listener);

    /** 请求 App 暂停朗读。仅"请求"—— 实际是否执行由 App 决定，未连接时静默忽略。 */
    void requestPause();

    /** 请求 App 继续朗读。语义同 requestPause。 */
    void requestResume();

    /** 请求 App 开始/继续播放（协议动作 play）。 */
    void requestPlay();

    /** 请求 App 停止朗读（协议动作 stop）。 */
    void requestStop();

    /** 请求 App 跳到下一句（协议动作 next）。 */
    void requestNext();

    /** 请求 App 回到上一句（协议动作 prev）。 */
    void requestPrev();

    /** 当前是否与 App 建立了 WebSocket 连接。 */
    boolean isConnected();

    /** 当前（或期望连接的）WebSocket 地址，形如 ws://127.0.0.1:47210/ws，便于诊断。 */
    String wsUrl();
}
```

**`getState()` 的"未连接"语义**（重要，请据此写代码）：

| 字段 | 未连接时的值 |
|---|---|
| `isActive()` | `false` |
| `bookTitle()` / `chapterTitle()` / `currentSentence()` / `ttsVoiceId()` | `null` |
| `progress()` | `0.0` |
| `isPaused()` | `false` |
| `lastUpdateEpochMs()` | `0` |

**因此永远不需要判空整个 `ReadingState` 对象**，只需判断 `isActive()`；
但文本字段可能为 `null`，写 UI 时记得兜底。

### 3.3 `ReadingState`

不可变快照 —— 每次状态变化产生一个新实例，你可以放心把它存进字段或跨线程传递。

```java
public interface ReadingState {
    boolean isActive();        // 是否有书处于打开状态（注意：暂停时仍为 true）
    String bookTitle();        // 可能为 null
    String chapterTitle();     // 可能为 null
    String currentSentence();  // 当前正在朗读的**这一句**（不是整章），可能为 null
    double progress();         // 全书进度 0.0 ~ 1.0，越界值已被裁剪
    boolean isPaused();        // 是否处于暂停
    String ttsVoiceId();       // 当前音色标识，如 edge:zh-CN-XiaoxiaoNeural，可能为 null
    long lastUpdateEpochMs();  // 最后更新时刻（epoch 毫秒），0 表示从未收到状态
}
```

> **协议字段名差异**：WebSocket 协议里句子字段叫 `sentence`，Java API 里叫 `currentSentence()`。
> 语义完全相同，改名是为了在 Java 侧读起来更明确。

### 3.4 `ReaderStateListener`

```java
@FunctionalInterface
public interface ReaderStateListener {
    /** 状态发生变化时被调用。**保证在 Minecraft 主线程**，可直接调用游戏 API。 */
    void onReadingStateChanged(ReadingState newState);
}
```

---

## 4. 线程模型（硬性约定，必读）

| 调用 | 允许的线程 | 说明 |
|---|---|---|
| `getState()` | **任意线程** | 读 volatile 不可变快照，无锁 |
| `addListener` / `removeListener` | **任意线程** | 线程安全列表 |
| `requestPause` / `requestResume` / `requestPlay` / `requestStop` / `requestNext` / `requestPrev` | **任意线程** | 内部异步发送，不阻塞；未连接时静默忽略 |
| `onReadingStateChanged` | **只在主线程** | 由 bridge 派发，你可以放心操作世界、渲染、发包 |

### 派发实现（bridge 内部如何保证）

WebSocket 接收线程收到消息后**不会**直接回调你，而是：

1. **客户端环境** → `Minecraft.getInstance().execute(...)`
   - 若当前已在主线程，直接执行（不产生额外一帧延迟）
   - 客户端类通过 `DistExecutor.unsafeCallWhenOn` + `FMLEnvironment.dist == Dist.CLIENT` 守卫间接加载，
     因此**专用服务端不会触碰 `net.minecraft.client.*`**，不会 `NoClassDefFoundError`
     （不能用 `safeCallWhenOn`：Forge 的 `validateSafeReferent` 会判定本方法的嵌套 lambda 为 unsafe 并抛异常）
2. **专用服务端环境** → `ServerLifecycleHooks.getCurrentServer().execute(...)`
3. **两者都不可用**（例如 `FMLCommonSetupEvent` 早期、主线程尚未就绪）→ 任务入队，
   等主线程 tick 或 `FMLLoadCompleteEvent` 时按 FIFO 顺序 flush

**每个监听器独立 try/catch**：某个监听器抛异常只会被记录日志，不影响其他监听器，
也不会导致 WebSocket 断连。

> 如果你在回调里需要做重活（如生成结构、写盘），请自行 `CompletableFuture` / 另起线程，
> 不要阻塞主线程 —— 这与任何 Minecraft 模组开发的常识一致。

---

## 5. 接入指南

### 5.1 Gradle 依赖

**开发期（推荐，与 `forge-test-addon` 一致）**：

```groovy
dependencies {
    minecraft "net.minecraftforge:forge:1.20.1-47.4.x"

    // 用 classes 目录而不是 jar：dev 环境使用 official 映射名，
    // 而发布 jar 经过 reobf（srg 中间名），直接引用会在 runServer/runClient 里
    // 抛 NoSuchMethodError。目录形式同时让 FML 扫描到 bridge 的 mods.toml，
    // 使它在本工程的 runServer/runClient 中一并被加载。
    implementation files(
        '../forge-bridge/build/classes/java/main',
        '../forge-bridge/build/resources/main'
    )
}
```

**发布/正式依赖**（把 bridge jar 放进 `libs/`）：

```groovy
dependencies {
    implementation files('libs/readerbridge-1.0.0.jar')
    // 或者用本地 maven / 远程 maven 坐标
}
```
```groovy
// 若走本地 maven 仓库
repositories { maven { url = uri("$rootDir/libs-repo") } }
dependencies { implementation "com.feiyu:readerbridge:1.0.0" }
```

### 5.2 `mods.toml` 声明依赖

**必须**声明对 `readerbridge` 的强依赖，否则加载顺序不保证，`get()` 可能拿到
尚未安装的状态（用 `getIfPresent()` 可以规避一半问题，但仍建议声明）：

```toml
[[dependencies.yourmodid]]
    modId = "readerbridge"
    mandatory = true
    versionRange = "[1.0.0,)"
    ordering = "AFTER"
    side = "BOTH"
```

> bridge 在双端都可加载（纯客户端优先，但专用服务端也能跑，只是没有 HUD）。
> 你的模组若只在客户端用，把 `side` 改成 `"CLIENT"` 亦可。

### 5.3 注册与注销时机

```java
public YourMod() {
    IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
    modBus.addListener(this::onCommonSetup);
}

private void onCommonSetup(FMLCommonSetupEvent event) {
    // 用 enqueueWork 保证在 bridge 已安装 API 之后执行
    event.enqueueWork(() -> {
        ReaderBridgeProvider.getIfPresent().ifPresent(api -> {
            api.addListener(YourMod::onReadingState);
        });
    });
}
```

**注销**（可选，但热重载/单人存档切换时更干净）：

```java
// 客户端退出存档时
MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
    ReaderBridgeProvider.getIfPresent()
        .ifPresent(api -> api.removeListener(YourMod::onReadingState));
});
```

> **Lambda 与方法引用会创建新实例**：`api.addListener(this::foo)` 的 `this::foo` 每次求值
> 都是**不同的对象**，因此 `removeListener(this::foo)` **无法**注销它。
> 需要可注销时，请持有 `ReaderStateListener` 字段或使用 `ILambdas` 类的静态引用：
>
> ```java
> // 正确：持有引用
> private final ReaderStateListener listener = this::onReadingState;
> api.addListener(listener);
> api.removeListener(listener);
> ```

---

## 6. 完整示例（可直接编译）

以下就是随本仓库交付的验收用**最小附属模组**
（完整工程见 `forge-test-addon/`），同时演示了"如何自证回调在主线程"。

```java
package com.feiyu.testaddon;

import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReaderStateListener;
import com.feiyu.readerbridge.api.ReadingState;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(TestAddonMod.MOD_ID)
public final class TestAddonMod {

    public static final String MOD_ID = "readerbridge_testaddon";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 持有监听器引用，保证之后能注销（方法引用每次求值都是新对象）。 */
    private final ReaderStateListener listener = TestAddonMod::onReadingStateChanged;

    public TestAddonMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        LOGGER.info("[test-addon] 模组构造完成");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ReaderBridgeApi api = ReaderBridgeProvider.get();
                api.addListener(listener);
                LOGGER.info("[test-addon] 监听器注册成功: connected={}, wsUrl={}",
                        api.isConnected(), api.wsUrl());
                LOGGER.info("[test-addon] 当前快照: active={}, book={}, sentence={}",
                        api.getState().isActive(), api.getState().bookTitle(),
                        api.getState().currentSentence());
            } catch (Throwable t) {
                LOGGER.error("[test-addon] 注册失败（bridge 未安装？）", t);
            }
        });
    }

    /** 保证由 bridge 在主线程调用。 */
    private static void onReadingStateChanged(ReadingState state) {
        boolean main = ThreadCheck.isOnMainThread();
        String tag = main ? "[MAIN-THREAD-OK]" : "[MAIN-THREAD-VIOLATION]";

        System.out.println(tag + " thread=" + Thread.currentThread().getName()
                + " active=" + state.isActive()
                + " book=" + state.bookTitle()
                + " chapter=" + state.chapterTitle()
                + " sentence=" + state.currentSentence()
                + " progress=" + state.progress()
                + " paused=" + state.isPaused());

        LOGGER.info("{} [test-addon] 回调 thread={} active={} book={} sentence={}",
                tag, Thread.currentThread().getName(),
                state.isActive(), state.bookTitle(), state.currentSentence());
    }
}
```

`ThreadCheck.isOnMainThread()` 的判定逻辑（`forge-test-addon/src/main/java/com/feiyu/testaddon/ThreadCheck.java`）：

- 客户端：`Thread.currentThread() == Minecraft.getInstance().getRunningThread()`（用 `DistExecutor` 间接访问）
- 服务端：`Thread.currentThread() == ServerLifecycleHooks.getCurrentServer().getRunningThread()`

验收时在日志里 grep：

```
[MAIN-THREAD-OK]      → 通过
[MAIN-THREAD-VIOLATION] → 实现有 bug，属于严重缺陷
```

---

## 7. 生命周期与配置

### 配置文件 `config/readerbridge-common.toml`（`ModConfig.Type.COMMON`）

```toml
[bridge]
    enabled = true                 # 总开关，false 时完全不建立连接
    wsPort = 47210                 # App 的 WebSocket 端口（范围 1024..65535）
    reconnectIntervalMs = 3000     # 重连间隔（范围 500..60000），内部指数退避、上限 30s
    heartbeatIntervalMs = 15000    # 心跳 ping 间隔（范围 3000..600000），连续 3 周期无任何回帧则判死并主动重连
    logStateChanges = true         # 是否在日志中打印每次状态变化

[hud]
    showHud = true                 # 客户端 HUD 是否显示当前句子
    hudX = 8
    hudY = 8
```

- 修改配置后**不需要重启游戏**：`ModConfigEvent.Reloading` 会触发重建连接。
- `ServerStoppedEvent`、模组卸载会关闭 WebSocket 与线程池，不留悬挂线程。

### 断线语义

| 情形 | 行为 |
|---|---|
| App 未启动 | 每 `reconnectIntervalMs` 重试，状态恒为 `isActive()==false`；**不打印 ERROR 刷屏** |
| App 中途退出 | 连接断开 → 状态降级为 inactive → 继续重连 |
| App 换端口（自动顺延） | 修改配置里的 `wsPort` 即可；实际端口见 App 设置页 / `GET /api/v1/health` |

---

## 8. 常见问题

**Q：`ReaderBridgeProvider.get()` 抛 `IllegalStateException`？**
A：bridge 模组没装、被禁用，或你在它的构造器之前就调用了。改用 `getIfPresent()`，
并把注册动作放到 `FMLCommonSetupEvent` 的 `enqueueWork` 里。

**Q：监听器一直不触发？**
A：按顺序检查 —— ① `api.isConnected()` 是否 true（false 说明 App 没开或端口不对）；
② App 里是否真的打开了书并播放（未打开时不会推送状态变化）；
③ `getState().isActive()`——注意 `lastUpdateEpochMs()==0` 表示从没收到过任何状态。

**Q：`currentSentence()` 是整章还是一句？**
A：**一句**。桥接模组收到的是 App 切句后的朗读单元，通常 8~120 字。要整章请自行累积。

**Q：能拿到章节全文吗？**
A：不能，Java API 刻意只暴露"当前朗读单元"。需要全文请直接调 App 的 REST
接口 `GET http://127.0.0.1:<port>/api/v1/books/{id}/chapters/{i}/content`。

**Q：可以在专用服务端使用吗？**
A：可以。API 在 common 层实现，服务端同样能收到状态与调用控制方法；
只有 HUD 是纯客户端功能。注意 App 运行在**玩家电脑**上，专用服务端的
`127.0.0.1` 指的是服务器本机 —— 这与"本地阅读投射"的设计前提一致。
