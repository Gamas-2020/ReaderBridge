# Reader Bridge WS 协议（模组端实现）

传输：WebSocket 文本帧，**newline-delimited JSON**（每行一个 JSON 对象）。
连接地址：`ws://127.0.0.1:<wsPort>/ws`，默认 `ws://127.0.0.1:47210/ws`，
仅允许回环地址。

## 服务端 → 模组

| type | 说明 | 模组处理 |
| --- | --- | --- |
| `hello` | 握手信息 | 记录日志 |
| `state` | 阅读状态（核心） | 解析为不可变快照，派发到主线程回调监听器 |
| `event` | 辅助事件 | 按 `event` 字段处理 |
| `error` | 错误 | 记录 warn 日志 |
| `pong` | 心跳回执 | 清零「未响应心跳」计数（debug 日志） |

```json
{"type":"hello","seq":1,"payload":{"service":"reader-bridge-core","version":"0.1.0"}}
{"type":"state","seq":2,"payload":{"active":true,"bookId":"mock:peach-blossom","bookTitle":"桃花源记","chapterIndex":0,"chapterTitle":"第一章 缘溪行","sentenceIndex":0,"sentence":"晋太元中，武陵人捕鱼为业。","progress":0.0,"paused":true,"voiceId":"sapi:Microsoft Huihui Desktop","lastUpdateEpochMs":1763000000000}}
{"type":"event","seq":3,"event":"tts.audio","payload":{"sentenceIndex":0,"path":"C:\\...\\s1_0.wav","durationMs":2000,"sampleRate":16000,"voiceId":"sapi:..."}}
{"type":"event","seq":4,"event":"server.ready","payload":{"httpBase":"http://127.0.0.1:47210/api/v1","wsUrl":"ws://127.0.0.1:47210/ws","port":47210}}
{"type":"event","seq":5,"event":"tts.error","payload":{"message":"...","sentenceIndex":0}}
{"type":"error","payload":{"message":"..."}}
{"type":"pong"}
```

### `state.payload` 字段

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `active` | bool | 否（默认 false） | 是否正在朗读 |
| `bookId` | string/null | 否 | 书 id（API 未暴露） |
| `bookTitle` | string/null | 否 | 书名 |
| `chapterIndex` | int | 否 | 章节序号（API 未暴露） |
| `chapterTitle` | string/null | 否 | 章节标题 |
| `sentenceIndex` | int | 否 | 句子序号（API 未暴露） |
| **`sentence`** | string/null | 否 | **当前句子**（API 中为 `currentSentence()`） |
| `progress` | double | 否（默认 0） | 0.0~1.0 |
| `paused` | bool | 否（默认 false） | 是否暂停 |
| `voiceId` | string/null | 否 | TTS 音色（API 中为 `ttsVoiceId()`） |
| `lastUpdateEpochMs` | long | 否 | 缺失时回落为「收到消息的本地时间」 |

防御策略：字段缺失 / 类型不符 / payload 不是对象 / 整行不是合法 JSON，
一律退化为默认值或直接忽略该行，**不抛异常、不断开连接**。

## 模组 → 服务端

```json
{"type":"ping"}
{"type":"command","action":"pause"}
{"type":"command","action":"resume"}
{"type":"command","action":"play"}
{"type":"command","action":"stop"}
{"type":"command","action":"next"}
{"type":"command","action":"prev"}
```

- 六个动作都有对应的 Java 方法：`requestPause()` / `requestResume()` / `requestPlay()` /
  `requestStop()` / `requestNext()` / `requestPrev()`。
  `resume` 只对已暂停的会话生效，`play` 是「开始播放」语义（App 侧二者都会落到 `app.play()`，
  但 `play` 在未打开章节时会返回 `error`）。
- 未连接时所有命令静默丢弃（debug 日志）。
- `ping` 由心跳定时器按 `heartbeatIntervalMs` 自动发送（默认 15000ms，范围 3000~600000）；
  连续 3 个周期无任何帧到达即判定死连接、主动断开并走重连退避。
  任意帧（含 `pong`、`state`、`event`）到达都会清零未响应计数。

## 与 App 侧的已知差异（重要）

命令集合已与 App 侧**完全对齐**（`pause | resume | play | stop | next | prev`），无剩余差异。

App 侧 `state.to_protocol_payload()` 的字段名与上文一致（`sentence`、`voiceId`、
`paused`、`progress`、`lastUpdateEpochMs`），已验证对齐。

`server.ready` 事件由 App 在**自身启动瞬间**广播一次（`reader-app/core/src/server.rs`），
晚于该时刻连接的客户端收不到，也不会补发；模组对它的处理是 INFO 日志 + 记录 `httpBase`/`wsUrl`。

`tts.audio` 的 `path` 是 Windows 绝对路径，扩展名随音色而定（edge 音色为 `.mp3` 24000Hz，
SAPI 音色为 `.wav` 16000Hz）。模组只记录路径与元数据，不读取、不播放音频文件。

