# forge-test-addon（readerbridge_testaddon）

最小第三方附属模组，用于**证明**：只要依赖 `forge-bridge`，就能拿到阅读状态 API，
并且回调被派发到 Minecraft 主线程。

- modId：`readerbridge_testaddon`
- 包名：`com.feiyu.testaddon`
- 依赖：`readerbridge`（见下文「依赖方式」）

## 依赖方式（本工程实际采用）

`build.gradle`：

```groovy
dependencies {
    minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}"
    implementation files(
            '../forge-bridge/build/classes/java/main',
            '../forge-bridge/build/resources/main'
    )
}
```

选择**目录形式**而不是 jar 的原因：

1. 发布的 `readerbridge-1.0.0.jar` 经过 reobf，方法名是 srg 中间名；而本工程在 dev 环境
   （`runServer`/`runClient`）使用 `official` 名，直接引用 reobf jar 会 `NoSuchMethodError`。
2. `build/classes/java/main` 是 official 名，语义上等价于「依赖发布 jar」。

### 关键补充：dev 环境下 bridge 必须登记进 `MOD_CLASSES`

`implementation files(...)` 只把类放进 **classpath**，而 FML 的 mod 发现**不扫 classpath**
（它扫 `run/mods` 目录里的 jar，以及环境变量 `MOD_CLASSES` 登记的「爆炸目录」）。
所以只靠 `implementation files(...)` 时，运行期会报：

```
Mod "readerbridge_testaddon" requires "readerbridge" "1.0.0 or above"
Currently, "readerbridge" is not installed
```

因此 `runs` 里显式补上 bridge 的坐标（见 `build.gradle`）：

```groovy
runs {
    configureEach {
        def toGwPath = { String relPath -> project.file(relPath).absolutePath.replace('\\', '/') }
        environment 'MOD_CLASSES', [
                "${mod_id}%%${toGwPath('build/resources/main')}",
                "${mod_id}%%${toGwPath('build/classes/java/main')}",
                "readerbridge%%${toGwPath('../forge-bridge/build/resources/main')}",
                "readerbridge%%${toGwPath('../forge-bridge/build/classes/java/main')}"
        ].join(File.pathSeparator)
    }
}
```

两个实测踩过的坑：

- **值里不能出现 `{`**：含 `{` 时 ForgeGradle 会走 `Utils.replaceTokens` 的 token 展开，
  展开过程按转义序列消费 Windows 反斜杠（`\n`→换行、`\f`→换页、`\b`→退格），路径被破坏，
  启动报 `Invalid paths argument, contained no existing paths: [...]`。
  故不使用 `{source_roots}` token，并把路径统一转成正斜杠（Windows 的 `Path.of` 接受）。
- **每个 mod 必须同时登记 resources 与 classes 两个目录**，且 resources 在前、classes 在后，
  与 ForgeGradle 自身的生成顺序一致；`mods.toml` 在 resources、class 在 classes，缺一即失败。

若要在正式环境中使用：把 `readerbridge-1.0.0.jar` 丢进 `mods/` 目录即可，
本附属模组编译期依赖改用该 jar 的 `compileOnly` + `runtimeOnly` 亦可。

### 构建顺序（重要）

两个工程是独立构建，跨工程无法自动解析依赖顺序，**必须先构建 bridge**：

```powershell
cd "E:\肥鱼专用文件夹\novel_mod\forge-bridge"
.\gradlew.bat build --no-daemon "-Dorg.gradle.java.home=E:\toolchains\jdk-17"

cd "E:\肥鱼专用文件夹\novel_mod\forge-test-addon"
.\gradlew.bat build --no-daemon "-Dorg.gradle.java.home=E:\toolchains\jdk-17"
```

## 验证主线程回调

启动 mock WS 服务端后跑 `runServer`，日志中每个状态回调都会打印：

```
[MAIN-THREAD-OK] [test-addon] listener thread=Server thread isMainThread=true active=true book=桃花源记 chapter=第一章 缘溪行 sentence=晋太元中，武陵人捕鱼为业。 progress=0.0 paused=true
```

判定标记：

- `[MAIN-THREAD-OK]` —— 回调在主线程（预期）
- `[MAIN-THREAD-VIOLATION]` —— 回调不在主线程（实现有问题）

grep 命令：

```powershell
Select-String -Path "run\logs\latest.log" -Pattern "MAIN-THREAD"
```

## 代码

- `TestAddonMod`：在 `FMLCommonSetupEvent`（`enqueueWork` 内）注册监听器并打印状态
- `ThreadCheck`：两端通用的主线程判断（客户端分支用 `DistExecutor` 隔离）
- `ClientThreadCheck`：`Minecraft.getInstance().isSameThread()`，只在客户端加载
