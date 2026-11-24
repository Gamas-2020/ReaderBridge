# 第三方组件与许可证声明

本仓库自身代码以 MIT 许可发布（见 [LICENSE](LICENSE)）。以下为随附或依赖的第三方组件。

## 1. JLayer 1.0.1 — LGPL-2.1

- **用途**：MP3 解码。Edge TTS 返回 mp3，而 OpenAL 只能吃 PCM，需要解码后再送入音频缓冲。
- **位置**：`forge-bridge/libs/jlayer-1.0.1.jar`
- **分发方式**：**已并入** `readerbridge-<version>.jar` 产物（`forge-bridge/build.gradle` 中的 embed 配置）
- **许可**：GNU Lesser General Public License 2.1
- **修改**：未作任何修改，jar 原样并入，使用者可自行替换
- **许可全文**：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt>

## 2. ApricityUI 1.3.1 — LGPL-2.1

- **用途**：WebUI 渲染层，用于阅读面板与设置页。
- **位置**：编译期从 `forge-bridge/libs/ApricityUI-forge-1.20.1-1.3.1.jar` 引用，配置为 `compileOnly`。
- **分发方式**：**不分发、不 embed**；本仓库不包含该 jar，需使用者自行下载。
- **许可**：GNU Lesser General Public License 2.1
- **运行期**：可选前置。未安装时本模组回退到原版 `GuiGraphics` 界面，全部功能可用。

## 3. 构建期依赖（不嵌入产物）

- Minecraft Forge / ForgeGradle / Minecraft —— 受 Mojang EULA 约束，不随本仓库分发。
- Microsoft Edge 在线语音合成服务 —— 运行期网络调用，非软件依赖；无网络或服务不可用时朗读不可用，阅读功能不受影响。

## 4. 设计参考

以下项目仅用于**设计思路参考**，未复制任何源代码：

- MuoniumPlayer —— 世界内 3D 文字的锚点算法与回屏弹簧思路
- Legado / IReader —— 书源规则字段命名、翻页手感与朗读分句思路
- 什锦铺 / novel-sound —— 本机 HTTP 接口的对接方式

## 5. 内容来源

- 内置样书：《桃花源记》《赤壁赋》《逍遥游》，均为公有领域文言文本。
- 内置规则书源：仅指向公开可访问的古籍站点，抓取公开页面。
- 通过本模组导入的书源与抓取行为，由使用者自行确保符合当地法律及目标站点条款。
