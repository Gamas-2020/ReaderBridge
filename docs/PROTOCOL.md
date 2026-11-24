# PROTOCOL.md —— 阅读器 App ↔ Forge 模组 通信协议

**协议版本**：`1`（随 `hello.payload.protocolVersion` 宣告，当前实现尚未写入该字段，见 §9）
**服务端**（提供方）：阅读器 App 的 Rust 核心 `reader-bridge-core`
**客户端**（消费方）：Forge 桥接模组 `readerbridge`、以及任何本地外部工具
**传输**：WebSocket + HTTP，**全部绑定 `127.0.0.1`**

---

## 1. 传输层

| 项 | 值 |
|---|---|
| HTTP 基址 | `http://127.0.0.1:<port>` |
| REST 前缀 | `/api/v1` |
| WebSocket 端点 | `ws://127.0.0.1:<port>/ws` |
| 默认端口 | `47210` |
| 绑定地址 | **硬编码 `127.0.0.1`**，不接受配置成 `0.0.0.0`（安全要求，`engine.rs` 里的 `LOOPBACK` 常量会强制覆盖任何外部传入的 bindHost） |

### 1.1 消息成帧

每条 WebSocket **文本帧**承载一个完整的 UTF-8 JSON 对象。

任务书原始表述是 "newline-delimited JSON"：本实现满足该语义且更严格——**一条消息 = 一帧 = 一个 JSON 对象**，帧内不含裸换行分隔的多个对象。这使得：

- 客户端可以直接对每个帧做 `JSON.parse` / `Gson.fromJson`，无需自行切行；
- 若未来把同一协议搬到裸 TCP 管道，加上 `\n` 分隔即可复用。

**不要**假设消息可以跨帧拼接。单条消息可能较大（`tts.audio` 事件含本地路径），但不会超过 64 KiB。

### 1.2 端口协商

App 启动时尝试绑定 `47210`，**若被占用则向后顺延，最多尝试 10 个端口**（47210 → 47219）。实际端口通过三条途径暴露：

1. 标准错误日志：`[reader-core] HTTP/WS 监听 http://127.0.0.1:47210/api/v1  ws://127.0.0.1:47210/ws`
2. HTTP `GET /api/v1/health` 的 `port` 字段
3. WS `server.ready` 事件（见 §6.1）

模组侧建议：按配置端口连接，失败则依次尝试 `port+1 … port+9`，任一成功即锁定该端口（见 §8 重连策略）。

---

## 2. 消息总则

所有消息都是同一信封结构：

```json
{ "type": "<消息类型>", "seq": <可选整数>, "payload": { ... } }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | string | 消息类型，见 §4 / §5 |
| `seq` | number | **仅服务端发出的消息有**。单调递增的全局序号，用于客户端检测丢消息/乱序。客户端发来的消息不需要带 |
| `payload` | object | 类型相关的载荷。`pong` 无 payload |

`event` 类型额外有**平级的 `event` 字段**（与 `type` 同级，不在 payload 内）：

```json
{ "type": "event", "event": "tts.audio", "seq": 12, "payload": { ... } }
```

---

## 3. 连接握手

客户端连上 `/ws` 后，服务端**立即主动推送两条消息**，客户端不需要发任何握手请求：

1. `hello`
2. `state`（当前状态快照，即使 `active=false` 也会发）

顺序固定，`seq` 连续。客户端应以这条 `state` 初始化本地缓存，之后靠后续推送增量更新。

---

## 4. 服务端 → 客户端

### 4.1 `hello`

```json
{
  "type": "hello",
  "seq": 3,
  "payload": { "service": "reader-bridge-core", "version": "0.1.0" }
}
```

| 字段 | 说明 |
|---|---|
| `payload.service` | 固定 `reader-bridge-core`，可用于确认连对了服务 |
| `payload.version` | Rust crate 版本（`CARGO_PKG_VERSION`） |

### 4.2 `state` —— 核心消息

```json
{
  "type": "state",
  "seq": 5,
  "payload": {
    "active": true,
    "bookId": "mock:peach-blossom",
    "bookTitle": "桃花源记",
    "chapterIndex": 0,
    "chapterTitle": "第一章 缘溪行",
    "lastUpdateEpochMs": 1795357061311,
    "paused": false,
    "progress": 0.0,
    "sentence": "晋太元中，武陵人捕鱼为业。",
    "sentenceIndex": 0,
    "voiceId": "sapi:Microsoft Huihui Desktop"
  }
}
```

`payload` 字段表：

| 字段 | 类型 | 可能为 null | 说明 |
|---|---|---|---|
| `active` | boolean | 否 | 是否有书处于打开状态（**不是**"是否正在朗读"，暂停时仍为 `true`） |
| `bookId` | string | 是 | 全局书 id，带书源前缀，形如 `<sourceId>:<源内 id>`，例如 `mock:peach-blossom` |
| `bookTitle` | string | 是 | 书名 |
| `chapterIndex` | number | 是 | 当前章在目录中的 0 基下标 |
| `chapterTitle` | string | 是 | 章名 |
| `sentence` | string | 是 | **当前正在朗读的句子**（不是整章文本）。字段名是 `sentence`，**不是** `currentSentence` |
| `sentenceIndex` | number | 是 | 当前句子在本章句子数组中的 0 基下标 |
| `progress` | number | 否 | 全书进度 `0.0 ~ 1.0`，算法见下 |
| `paused` | boolean | 否 | 是否暂停 |
| `voiceId` | string | 否 | 当前音色，格式 `<providerId>:<provider 内音色 id>`，例如 `sapi:Microsoft Huihui Desktop`、`edge:zh-CN-XiaoxiaoNeural` |
| `lastUpdateEpochMs` | number | 否 | 状态最后变更的 Unix 毫秒时间戳 |

**`progress` 计算方式**（`engine.rs::CoreReader::snapshot`）：

```
progress = (chapterIndex + sentenceIndex / 本章句子总数) / 全书章节总数
```

因此它是**章级 + 句级**的粗粒度进度，章内精确到句。客户端只应把它当"大致位置"展示，不要用它反推精确字符偏移。

**`state` 的推送时机**（去重规则）：仅在状态**实际发生变化**时推送（引擎在 `emit_state` 前比对上一次快照）。同一 `state` 不会因时间流逝而重复发送——客户端不能用它做心跳，心跳用 `ping`/`pong`。

### 4.3 `event`

```json
{ "type": "event", "event": "tts.audio", "seq": 12, "payload": { ... } }
```

| `event` | 触发时机 | `payload` |
|---|---|---|
| `server.ready` | HTTP/WS 服务绑定成功 | `{ "httpBase": "http://127.0.0.1:47210", "wsUrl": "ws://127.0.0.1:47210/ws", "port": 47210 }` |
| `tts.audio` | 某句合成完成、即将播放 | `{ "path": "...", "durationMs": 1600, "sentenceIndex": 0, ... }` |
| `tts.error` | 某句合成失败 | `{ "message": "错误详情", "sentenceIndex": 0 }` |

`tts.audio.path` 是**服务端本机**的绝对文件路径（wav/mp3）。客户端若与服务端同机（本项目的常态）可据此播放；跨机场景下该路径无意义，是已知限制。

`tts.error` 的 `message` 是给人看的诊断字符串，**不要**对它做字符串匹配来驱动逻辑；需要区分错误类型时应依赖 `state` 的变化。

### 4.4 `error`

```json
{ "type": "error", "payload": { "message": "非法 JSON: expected value at line 1 column 1", "action": "pause" } }
```

| 字段 | 说明 |
|---|---|
| `payload.message` | 人类可读的错误详情 |
| `payload.action` | 仅当错误由某条 `command` 引起时出现，回显该 action 便于对账 |

`error` **不带 `seq`**（它是对单条请求的直接应答，不进广播序列）。

### 4.5 `pong`

```json
{ "type": "pong" }
```

对 `ping` 的应答。无 payload、无 `seq`。

---

## 5. 客户端 → 服务端

### 5.1 `command`

```json
{ "type": "command", "action": "pause" }
```

| `action` | 语义 | 服务端行为 |
|---|---|---|
| `play` | 开始/继续朗读 | 等同 `resume` |
| `resume` | 从暂停恢复 | 置 `paused=false`，唤醒朗读循环 |
| `pause` | 暂停 | 置 `paused=true`，朗读循环挂起 |
| `stop` | 停止朗读并复位到章首句 | `active` 仍为 `true` |
| `next` | 下一章 | 切章并定位到第 0 句 |
| `prev` | 上一章 | 切章并定位到第 0 句 |

- 未识别的 `action` 会收到一条 `error`（`payload.message` 形如 `未知 action: xxx`），连接不断开。
- `command` 造成状态变化后，服务端会照常广播新的 `state`。

### 5.2 `ping`

```json
{ "type": "ping" }
```

用于保活/测活。应答 `pong`。建议间隔 15–30 秒；服务端本身不做空闲超时踢人，但企业防火墙/代理可能掐掉长时间静默连接。

---

## 6. 完整会话示例（真实抓包）

```
S→C  {"type":"hello","seq":3,"payload":{"service":"reader-bridge-core","version":"0.1.0"}}
S→C  {"type":"state","seq":4,"payload":{"active":true,"bookId":"mock:peach-blossom","bookTitle":"桃花源记","chapterIndex":0,"chapterTitle":"第一章 缘溪行","lastUpdateEpochMs":1795357061311,"paused":false,"progress":0.0,"sentence":"晋太元中，武陵人捕鱼为业。","sentenceIndex":0,"voiceId":"sapi:Microsoft Huihui Desktop"}}
C→S  {"type":"command","action":"play"}
S→C  {"type":"state","seq":5,"payload":{ ..., "paused":false, "sentenceIndex":0, ... }}
S→C  {"type":"event","event":"tts.audio","seq":6,"payload":{"path":"...\\tts\\cache\\0-0.wav","durationMs":1600,"sentenceIndex":0}}
S→C  {"type":"event","event":"tts.error","seq":7,"payload":{"message":"SAPI 合成失败 ...","sentenceIndex":1}}
```

---

## 7. HTTP REST 接口（供外部工具）

全部返回 `application/json`，失败为 `{"error":"..."}`。除 `/health` 外都作用于同一个 `AppState`，也就是和 UI、WebSocket 客户端共享一份阅读状态。

### 7.1 查询

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 健康检查。返回 `bind`/`port`/`ok`/`service`/`sources`/`version`/`voices` |
| GET | `/api/v1/sources` | 已注册书源列表 |
| GET | `/api/v1/voices` | 可用音色列表（含 `current`） |
| GET | `/api/v1/books` | 书架 |
| GET | `/api/v1/books/{bookId}/chapters` | 目录 |
| GET | `/api/v1/books/{bookId}/chapters/{index}/content` | 正文。`{index}` 是 **0 基**章下标 |
| GET | `/api/v1/state` | 当前阅读状态，结构同 WS `state.payload` |

### 7.2 变更（写操作）

这些端点让外部工具在不碰 UI 的情况下驱动阅读器，主要用于脚本化验收与第三方集成。

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| POST | `/api/v1/books` | `{"sourceId":"mock","bookKey":"peach-blossom"}` | 加入书架，返回 `{"book":{...}}` |
| DELETE | `/api/v1/books/{bookId}` | — | 从书架移除，返回 `{"removed":true\|false}` |
| POST | `/api/v1/reader/open` | `{"bookId":"mock:peach-blossom","index":0}` | 打开指定章。返回章节摘要 + 最新 `state`（正文走 GET content，避免响应过大） |
| POST | `/api/v1/reader/play` | `{}` | 开始朗读 |
| POST | `/api/v1/reader/pause` | `{}` | 暂停 |
| POST | `/api/v1/reader/stop` | `{}` | 停止 |
| POST | `/api/v1/reader/next` | `{}` | 下一章 |
| POST | `/api/v1/reader/prev` | `{}` | 上一章 |
| POST | `/api/v1/reader/voice` | `{"voiceId":"edge:zh-CN-XiaoxiaoNeural"}` | 切换音色 |

`/api/v1/reader/*` 的成功响应统一是 `{"state":{...}}`（最新状态），失败是 400 + `{"error":"..."}`。未打开任何章节时 `play` 会返回 `{"error":"尚未打开任何章节"}`。

> PowerShell 5.1 提示：用 `curl.exe -d '{"a":1}'` 时双引号会被 shell 剥掉，实际发出非法 JSON。改用 `--data-binary "@body.json"` 或 `--data-raw` 加转义。

`bookId` 在 URL 中需要 URL 编码（含 `:` 时多数客户端无需编码，但请按需处理）。注意 axum 0.8 的路径参数语法是 `{name}`（不是老版本的 `:name`）。

### 7.3 一次完整的脚本化验证

```bash
# 1. 加书 → 2. 打开第 0 章 → 3. 播放（会触发真实的 TTS 合成）
curl -s -X POST http://127.0.0.1:47210/api/v1/books \
     -H 'Content-Type: application/json' -d '{"sourceId":"mock","bookKey":"peach-blossom"}'
curl -s -X POST http://127.0.0.1:47210/api/v1/reader/open \
     -H 'Content-Type: application/json' -d '{"bookId":"mock:peach-blossom","index":0}'
curl -s -X POST http://127.0.0.1:47210/api/v1/reader/play \
     -H 'Content-Type: application/json' -d '{}'

# 3 秒后读状态：sentenceIndex 应已推进，progress 增大
curl -s http://127.0.0.1:47210/api/v1/state
```

实测结果（2026-11-22）：`play` 后 20 秒内 `state.sentenceIndex` 从 0 推进到 3，`progress` 0 → 0.15，同时 WS 客户端收到 4 条 `event:tts.audio`，缓存目录出现 4 个 mp3（19–38 KB，24 kHz）。

---

## 8. 连接与重连语义（模组侧实现指引）

服务端**不知道**也**不关心**有几个客户端连着。多客户端广播是免费的（内部 `broadcast` 通道，容量 256）。

### 8.1 App 未启动时

这是**常态而非异常**：App 与模组端完全解耦，App 不依赖模组，而模组刚需 App 的数据。因此模组必须把"连不上"当作一等状态：

- 连接失败 → 重试，间隔由配置 `reconnectIntervalMs` 控制（默认 3000ms）；
- 未连接期间 `ReaderBridgeApi.getState()` 返回**降级状态**：`active=false`，其余字段为 null/0，而**不是**抛异常或返回 null；
- 从"已连接"掉线时，应广播一次降级的 `state` 给模组内监听者，让第三方模组知道数据已失效；
- 重连成功后服务端会重发 `hello` + 当前 `state`，无需做历史补偿。

### 8.2 背压

服务端广播通道容量 256。若客户端消费太慢，服务端会丢弃旧消息并打日志（`WS 订阅者落后 N 条消息`），**连接保持不断**。客户端处理新消息时**不应**假设 `seq` 连续无缺口——但 `state` 是幂等快照，丢一条不影响正确性（下一条就是最新真相）。

### 8.3 线程

服务端所有 WS/HTTP 处理都在自己的 Tokio 线程池上，与 Minecraft 主线程**无关**。模组收到消息后**必须**切回 MC 主线程再分发（详见 `API.md`）。

---

## 9. 版本演进与兼容

- `state.payload` 里**所有已知字段都是可选的**——客户端必须容忍缺失字段（用默认值），才能在新旧版本混跑时不崩。反过来说，服务端**可以随时新增字段**，不视为破坏性变更。
- 破坏性变更（改名/改语义/删字段）会提升协议版本号，并在 `hello.payload` 增加 `protocolVersion` 字段。当前实现**尚未写入**该字段，客户端应在缺失时按版本 `1` 处理。
- 已发生的语义注意点：`state.payload` 的句子字段名是 **`sentence`**（不是 `currentSentence`）；`active` 表示"有书打开"而非"正在出声"。

---

## 10. 安全边界

- 只监听 `127.0.0.1`，不接受外部网段连接。这是硬性设计，不是配置项。
- 无鉴权。任何本机进程都能连上并读状态、发 `command`。前提假设是"用户自己的机器上只跑用户自己的程序"。若将来要跨机，必须引入 token 鉴权与 TLS，届时协议版本上调。
- `tts.audio` 泄露绝对路径（含用户名）。这是本地闭合链路的可接受代价；若要对外暴露，需改为相对路径 + 下载端点。

---

## 11. 当前实现的实际覆盖度（诚实清单）

协议本身是完整的，但**两端各自只实现了一个子集**。第三方对接时按这张表判断：

| 能力 | App 服务端 | Forge 模组端 |
|---|---|---|
| 发送 `hello` / `state` / `event` / `error` / `pong` | ✅ 全部 | — |
| 接收 `state` 并解析 | — | ✅ |
| 接收 `event` | — | ✅ 解析 `tts.audio` / `server.ready` / `tts.error`，**不播放音频**（mod 侧只投射文字），`logStateChanges=true` 时记 DEBUG |
| 接收 `command`：`pause` / `resume` | ✅ | ✅（`ReaderBridgeApi.requestPause/requestResume`） |
| 接收 `command`：`play` / `stop` / `next` / `prev` | ✅ App 全部支持 | ✅ `ReaderBridgeApi.requestPlay/requestStop/requestNext/requestPrev`（方法级已就绪，**端到端未逐条实测**；App 侧另有 REST 端点 §7.2） |
| 发送 `ping` | ✅ 支持并回 `pong` | ✅ `heartbeatIntervalMs`（默认 15000，下限 3000）；任意帧到达即清零未响应计数，连续 3 周期无帧判死并主动重连 |
| 端口自动协商 | ✅ 47210 起顺延最多 10 个 | ⚠️ 模组读配置里的固定端口；若 App 顺延了，需手工把 `wsPort` 改成实际端口 |
| 断线重连 | — | ✅ `reconnectIntervalMs` 指数退避，上限 30s |
| 状态去重推送 | ✅ 状态真变才推 | — |
| REST 写操作（加书/开章/播放控制） | ✅ 见 §7.2 | — 不适用（模组走 WS） |

**联调覆盖度（2026-11-22 实测）**：真实 Flutter App ↔ `runServer` / `runClient` 已双向打通。Round 1：服务端 7 次、客户端 3 次 `[MAIN-THREAD-OK]`，`[MAIN-THREAD-VIOLATION]` 0 次，验证了 `active=true → false` 的完整生命周期。Round 2：补齐心跳与事件日志后复测，服务端 **13 次** `[MAIN-THREAD-OK]` 全部落在 `Server thread`，真实 App 场景 **16 次 ping / 15 次 pong**，`tts.audio` 事件 **9 条**带真实路径与字节数；另用「握手后彻底沉默」的假服务端验证了「连续 3 个心跳周期无帧 → 判死 → 主动断开 → 2 秒后重连成功」。详见 `docs/ACCEPTANCE.md` §1。

**心跳为什么不靠 `state` 兼任**：朗读时 `state` 确实每句一条，但**暂停或空闲时会有长时间静默**，此时 TCP 半开连接不会被 `onClose` 察觉（`java.net.http.WebSocket` 只在 socket 层真正断开时回调）。因此独立 `ping`/`pong` 是必要的：模组每 `heartbeatIntervalMs` 发一帧 `{"type":"ping"}`，任何帧到达都清零未响应计数，连续 3 个周期无任何帧即判定死连接并主动 `abort()`，随后走既有指数退避重连。协议层无需改动。

**`play` / `stop` / `next` / `prev` 现已开口**：`ReaderBridgeApi` 提供 `requestPlay` / `requestStop` / `requestNext` / `requestPrev`，与 `requestPause` / `requestResume` 并列。它们只是把对应 action 交给同一个 `command` 帧发送通道，语义与协议层完全一致；若第三方需要更细的控制面，仍可直接发原始 `command` 帧。


