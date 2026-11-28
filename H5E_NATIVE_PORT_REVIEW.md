# 央视 h5e 原生解密层移植 —— 修改审核报告

> 状态：**全部改动仅在工作区，未 git commit、未 push**。HEAD 仍为 `73f9d5588`。
> 目标设备：MiTV4A（Android 6.0 / API 23 / armeabi-v7a 32 位）。
> 报告生成时间：2026-11-28

---

## 一、这次改造要解决什么

央视影视点播的 `asp/h5e/`（网页源）TS 分片不是标准 HLS 加密（无 `#EXT-X-KEY`），而是官方 wasm 按 NALU 做的自定义变换。此前只有两条路：

- addon 端 `/h5e/` 解密代理：视频字节过 VPS，服务器流量大；
- NuvioTV 端无原生解密：h5e 源花屏，只能关掉。

本次把 MyTVB 已验证的 **wasm2c 纯 C 解密**移植进 NuvioTV：解密在电视本机完成，VPS 只传 JSON（零视频字节），MiTV4A 可直接播放真 720P/1080P 网页源。

**设计原则：默认关闭。** 只有 stream 带 `behaviorHints.h5eEncrypted=true` 才加载 native 库、注入解密 DataSource；普通流完全不触碰这套代码。

---

## 二、改动总览

- 修改文件：**23 个**
- 新增目录：**2 个**（`app/src/main/cpp/cctv_h5e/`、`app/src/main/java/com/nuvio/tv/core/cctv/`）
- native 新增约 **6.7 万行**（其中 6.4 万行是 wasm2c 自动生成产物，不建议人工审阅，见 §3.1）

### 2.1 完整文件清单

| # | 文件 | 类型 | 作用 |
|---|------|------|------|
| 1 | `app/src/main/cpp/cctv_h5e/cctv_h5e_decryptor.c` | 新增 | JNI 解密封装（TS demux + NALU 调度 + wasm 运行时桥） |
| 2 | `app/src/main/cpp/cctv_h5e/generated/cctv_h5e_wasm.c` | 新增 | wasm2c 自动产物（32452 行） |
| 3 | `app/src/main/cpp/cctv_h5e/generated/cctv_h5e_wasm.h` | 新增 | wasm2c 自动产物（341 行） |
| 4 | `app/src/main/cpp/cctv_h5e/generated/cctv_h5e_memory_init.h` | 新增 | wasm2c 自动产物（31327 行，data 段初始化） |
| 5 | `app/src/main/cpp/cctv_h5e/wasm-rt/*` | 新增 | wasm2c 官方运行时（5 个文件） |
| 6 | `app/src/main/cpp/cctv_h5e/CMakeLists.txt` | 新增 | 独立编译 libcctv_h5e.so |
| 7 | `app/src/main/cpp/CMakeLists.txt` | 修改 | `add_subdirectory(cctv_h5e)`，project 改名 |
| 8 | `app/build.gradle.kts` | 修改 | BuildConfig 开关 + 无条件挂载 CMake |
| 9 | `app/src/main/java/com/nuvio/tv/core/cctv/CctvH5eDecryptor.kt` | 新增 | JNI 包装（loadLibrary + 6 个 external） |
| 10 | `app/src/main/java/com/nuvio/tv/core/cctv/CctvH5eDataSource.kt` | 新增 | Media3 解密 DataSource |
| 11 | `data/remote/dto/StreamResponseDto.kt` | 修改 | BehaviorHintsDto 加 `h5eEncrypted` |
| 12 | `domain/model/Stream.kt` | 修改 | StreamBehaviorHints 加 `h5eEncrypted` |
| 13 | `data/mapper/StreamMapper.kt` | 修改 | DTO → domain 传递 |
| 14 | `domain/model/Plugin.kt` | 修改 | LocalScraperResult 加字段 + toStream 传递 |
| 15 | `app/src/full/java/com/nuvio/tv/core/plugin/PluginRuntime.kt` | 修改 | 插件 JSON 手工解析读标记 |
| 16 | `data/repository/StreamRepositoryImpl.kt` | 修改 | toPluginStream 传递 |
| 17 | `ui/navigation/Screen.kt` | 修改 | 路由模板/参数/URL 拼接 |
| 18 | `ui/navigation/NuvioNavHost.kt` | 修改 | navArgument + 2 处 createRoute |
| 19 | `ui/screens/player/PlayerNavigationArgs.kt` | 修改 | 导航参数加字段 + 读回 |
| 20 | `ui/screens/player/PlayerStreamRouteState.kt` | 修改 | 播放器内换集回写标记 |
| 21 | `ui/screens/stream/StreamScreenViewModel.kt` | 修改 | StreamPlaybackInfo 载体 + 3 处构造 + forcesInternalPlayer |
| 22 | `data/local/StreamLinkCacheDataStore.kt` | 修改 | 链接缓存序列化/反序列化 |
| 23 | `ui/screens/player/PlayerRuntimeController.kt` 等 8 个 Controller 文件 | 修改 | currentH5eEncrypted 状态 + 3 处 createMediaSource 调用 |
| 24 | `app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerStreamRouteStateTest.kt` | 修改 | 新增 2 个标记保活测试 |

---

## 三、分层说明

### 3.1 Native 层（`app/src/main/cpp/cctv_h5e/`）

来源：`github.com/qianxuntudou-ops/MyTVB` 的 `app/src/main/cpp/cctv_h5e/`，经 `git clone --depth 1` 拉取（clone 在 `/tmp/MyTVB`，未并入本项目仓库）。

**唯一手工改动**：`cctv_h5e_decryptor.c` 的 6 个 JNI 函数名，从
`Java_com_mytvb_feature_cctv_h5e_CctvH5eDecryptor_*`
改为
`Java_com_nuvio_tv_core_cctv_CctvH5eDecryptor_*`
（与 Kotlin object 的包名 `com.nuvio.tv.core.cctv` 对齐）。

**剔除**：MyTVB 原 CMakeLists 中的 `host_h5e_test` 可执行程序与 CMG（央视频直播）解密库——本场景只需要点播，且 CMG 引用了不存在的 `../cmg` 目录。新 CMakeLists 只编译 `cctv_h5e` 一个 so：

```cmake
add_library(cctv_h5e SHARED
    cctv_h5e_decryptor.c
    generated/cctv_h5e_wasm.c
    wasm-rt/wasm-rt-impl.c
    wasm-rt/wasm-rt-mem-impl.c)
target_compile_options(cctv_h5e PRIVATE -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden)
target_link_libraries(cctv_h5e log m)
```

**JNI 接口**（6 个，与 Kotlin external 一一对应）：

| 函数 | 用途 |
|------|------|
| `decryptVodTransportStream(ByteArray): ByteArray?` | **点播解密主入口**（本项目唯一使用） |
| `decryptTransportStream` | 直播入口，保留仅为符号对齐，不调用 |
| `setSpsCompatibilityMode` | 老解码器 SPS 兼容开关 |
| `cancelPendingDecrypts` | 停止播放时取消 |
| `releaseThreadContext` | 释放调用线程的 wasm 上下文 |
| `lastDecryptFailureReason` | 解密失败原因诊断 |

### 3.2 构建配置（`app/build.gradle.kts`）

1. 新增开关变量：
   ```kotlin
   val enableCctvH5eDecrypt = resolveProperty(devProperties, localProperties,
       "CCTV_H5E_DECRYPT_ENABLED", "true").trim().lowercase() !in setOf("false","0","no","off")
   ```
   **默认 true**——在线 CI 无需改 local.properties 即可编译出解密能力；设 `CCTV_H5E_DECRYPT_ENABLED=false` 可彻底关闭（BuildConfig 常量为 false，工厂不注入）。
2. `defaultConfig` 加 `buildConfigField("boolean", "CCTV_H5E_DECRYPT_ENABLED", ...)`。
3. `externalNativeBuild.cmake.path` 从 `if (enableDoviNative)` 内移到无条件挂载——因为 h5e 需要在 DOVI 关闭（当前 local.properties 就是 `DOVI_NATIVE_ENABLED=false`）时仍能编译。DOVI 的 cmake arguments 仍在原条件块内，互不影响。

### 3.3 Kotlin JNI 包装与 DataSource

- `CctvH5eDecryptor.kt`：`object` + `System.loadLibrary("cctv_h5e")` + 6 个 `external fun`。external 方法名与 native JNI 符号精确对应，**不要重命名**。
- `CctvH5eDataSource.kt`：包装上游 HTTP DataSource，`open()` 整段读入，识别 TS（首字节 `0x47` + 188 字节对齐）则解密后回放，m3u8 清单与非 TS 原样透传。含：
  - 失败计数（同段连续 3 次确定性失败 → 抛非 IOException 触发播放器回退）
  - 响应头快照（避免上游 close 后 headers 被清空导致 HLS 识别失效）
  - `resetTrapFailures()`（换集时由工厂清零）
  - 未使用 `Map.merge`（Java 8 API），改用显式 get/put，不依赖脱糖，**API 23 直接可用**

### 3.4 标记传递链（最容易出错的部分）

`behaviorHints.h5eEncrypted` 必须穿过整条导航链，漏一处就花屏。完整通路：

```
插件 JS 返回 JSON (behaviorHints.h5eEncrypted)
  → PluginRuntime.parseJsonResults (Gson Map 手工解析)
  → LocalScraperResult.h5eEncrypted
  → StreamRepositoryImpl.toPluginStream
  → Stream.behaviorHints.h5eEncrypted
  → StreamScreenViewModel.getStreamForPlayback → StreamPlaybackInfo
  → Screen.Player.createRoute → URL 参数 &h5eEncrypted=true/false
  → NuvioNavHost navArgument → PlayerNavigationArgs.from
  → PlayerRuntimeController.currentH5eEncrypted
  → applySelectedStreamState → 3 处 createMediaSource(h5eEncrypted=...)
  → PlayerMediaSourceFactory 注入 CctvH5eDataSource.Factory
```

旁路也已闭环：
- **播放器内换集/换源**：`playerStreamRouteValues` 回写 savedStateHandle
- **链接缓存复用**：`StreamLinkCacheDataStore` 的 4 处序列化/反序列化
- **服务端/种子会话**：重置 `currentH5eEncrypted=false`，避免继承流标记
- **强制内置播放器**：`StreamPlaybackInfo.forcesInternalPlayer` 纳入 h5eEncrypted——原生解密只在 ExoPlayer 内生效，不能把加密流路由到外部播放器/mpv

### 3.5 工厂端注入点（`PlayerMediaSourceFactory.kt`）

```kotlin
val useCctvH5e = isHls && h5eEncrypted && BuildConfig.CCTV_H5E_DECRYPT_ENABLED
val adaptiveFactory = DefaultDataSource.Factory(context, createHttpDataSourceFactory(...))
val decryptFactory = if (useCctvH5e) CctvH5eDataSource.Factory(adaptiveFactory) else adaptiveFactory
// 解密在 HlsParameterSetInitDataSource 内层：网络 → Default → CctvH5e(解密) → HlsParamInit → HlsMediaSource
if (isHls) HlsParameterSetInitDataSource.Factory(decryptFactory) else decryptFactory
```

**已知边界**：当 stream 带 sidecar 外挂字幕时，`DefaultMediaSourceFactory` 分支（375 行）用 `defaultSourceFactory` 而非 `httpDataSourceFactory`，不经过解密链。央视 h5e 源字幕内嵌在 TS 中、不带 sidecar，正常路径不受影响；若后续要给加密流加外挂字幕，需另行处理。

---

## 四、API 23 兼容性审查（用户重点关注项）

逐项核对结论：**新代码没有使用任何高于 API 23 的平台 API**。

### 4.1 Native 层

| 项 | 结论 | 依据 |
|----|------|------|
| `__thread` TLS（30+ 变量） | 支持 | minSdk 23 时 Clang 走模拟 TLS（emutls，compiler-rt 提供），API 23 可用；非 ELF TLS |
| pthread key/once/getspecific | 支持 | bionic API 1 起；NDK r23+ 已并入 libc，无需 `-pthread` |
| `__sync_add_and_fetch` 等原子 | 支持 | 均为 32 位，ARMv7 编译为 ldrex/strex，无需 libatomic |
| `gettimeofday` | 支持 | API 1；未用 clock_gettime |
| 高版本 libc 函数 | 未使用 | 全目录扫描 memmem/strlcpy/reallocarray/getrandom/arc4random 等无命中；`getauxval` 仅在 `#if WASM_RT_USE_SEGUE`（x86_64，ARM 不编译）内 |
| mmap / 信号处理器 | 条件关闭 | `-DWASM_RT_USE_MMAP=0`；32 位自动 `WASM_RT_MEMCHECK_BOUNDS_CHECK=1` → 不装 signal handler，无 sigaltstack 依赖 |
| generated wasm 的 clz/ctz | 支持 | ARMv7 有 CLZ 指令；`__builtin_clzll` 由 compiler-rt `__clzdi2` 提供 |
| 64 位原子 / 指针宽度假设 | 无 | generated 内无 `__sync_*`/`__atomic_*`；无 `sizeof(void*)` 假设；seg_gs/segue 在 ARM 不编译 |
| CMake 链接 `log m` | 正确 | JNIEXPORT 自带 default visibility，`-fvisibility=hidden` 不影响导出 |
| **NDK r29 + minSdk 23** | **可行** | 已查证 NDK 官方 changelog：r26 起最低支持 API 21，r27/r28/r29 未提高；minSdk 23 在范围内。r28 的 16KiB 页对齐默认只对 arm64/x86_64 生效，armeabi-v7a 不受影响 |

### 4.2 Kotlin 层

| API | 等级 | 结论 |
|-----|------|------|
| `System.loadLibrary`、`Log`、`SystemClock.elapsedRealtime` | API 1 | 支持 |
| `AtomicInteger`、`ByteArrayOutputStream`、`System.arraycopy` | API 1 | 支持 |
| `minOf`、`runCatching`、`synchronized` | Kotlin stdlib | 支持 |
| `JSONObject.optBoolean` | API 1 | 支持 |
| `Map.merge` | Java 8（API 24） | **已改写**为显式 get/put，消除对脱糖的隐式依赖 |
| Media3 DataSource 接口 | 库 API | 项目已在用，库自身 minSdk ≤ 23 |

### 4.3 一项需真机验证的提示（未改动，非阻塞）

`cctv_h5e_decryptor.c` 的线程退出兜底：用 `pthread_key_create` 注册析构器，在线程退出时调 `release_thread_context_impl()` 释放 wasm 内存。API 23 上 `__thread` 是模拟 TLS，理论上 emutls 自身也用 pthread key，存在「emutls 析构与本项目析构顺序不确定」的极小概率竞态。

**为何保留原样**：这份 C 代码与 MyTVB 在 MiTV4A（同为 API 23）真机上已验证可运行的版本逐字节一致（仅改了 JNI 函数名）。彻底规避需把 `g_env/g_module` 从 `__thread` 整体重构为堆对象 + pthread key value，涉及 1300 行 native 内存管理代码；本机无 Android SDK/NDK 无法编译、无真机验证，此时大改引入新 bug 的风险远高于收益。

**建议**：在线 CI 编译通过后，在 MiTV4A 真机上做一次「反复进出台/切集」压力测试，观察 logcat 是否出现 `h5e thread context released` 前后的崩溃。若出现问题，按上述方案重构；若稳定则无需改动。

另注：VOD 会话会把 wasm 堆一次预扩到 96 MiB（每个解密线程一份），MiTV4A 小内存设备上多并发加载线程会累加 RSS——这是资源占用，非 API 兼容问题。

---

## 五、已发现并修复的问题

| # | 问题 | 修复 |
|---|------|------|
| 1 | `NuvioNavHost` 第二处 `createRoute`（自动选源）漏传 `h5eEncrypted`，自动播放加密流会花屏 | 已补 `h5eEncrypted = playbackInfo.h5eEncrypted` |
| 2 | `CctvH5eDataSource` 的 `getResponseHeaders()` 委托给已 close 的上游，HLS 靠 Content-Type 识别播放列表时拿空头 | open 中 close 前快照响应头，`getResponseHeaders()` 返回快照 |
| 3 | `resetTrapFailures()` 定义但从未调用，失败计数跨集残留 | 工厂注入解密流时调用清零 |
| 4 | `Map.merge` 依赖 Java 8 脱糖 | 改为显式 get/put |
| 5 | JNI 命名风险：Kotlin external 名曾写成 `nativeXxx`，与 C 侧 JNI 符号不匹配会 UnsatisfiedLinkError | 改为与 C 侧 `Java_com_nuvio_tv_core_cctv_CctvH5eDecryptor_*` 精确一致 |

---

## 六、验证情况

| 验证项 | 结果 |
|--------|------|
| wasm2c 产物语法检查（gcc -fsyntax-only，无需 NDK） | cctv_h5e_wasm.c / wasm-rt-impl.c / wasm-rt-mem-impl.c 全部 **SYNTAX OK** |
| JNI 符号 ↔ Kotlin external 名一一对应 | 6/6 匹配 |
| 全项目 `h5eEncrypted` 引用一致性 | 28 处，链路无断点 |
| `LocalScraperResult` / `StreamBehaviorHints` 位置参数构造审查 | 均为命名参数或前 4 个位置参数，新字段带默认值不影响编译 |
| 静态编译正确性审查（独立 agent 逐文件） | A–H 各项通过，仅发现并修复上述问题 1、2 |
| API 23 兼容性专项审查 | 全部函数调用支持；1 项真机验证提示（§4.3） |
| **完整 Gradle 编译** | **未执行**——本机无 Android SDK/NDK/CMake。由在线 CI 承担 |

---

## 七、你需要做的

1. **审核本报告与上述文件**（`git diff` 可直接看 23 个修改文件；新增文件在 `app/src/main/cpp/cctv_h5e/` 和 `app/src/main/java/com/nuvio/tv/core/cctv/`）。自动生成产物（generated/ 下 3 个文件、wasm-rt/）可跳过逐行审阅。
2. 审核通过后，由你决定是否提交并推送到 fork（`fqw000/NuvioTV`）触发在线 CI（`pr-full-debug-build.yml` / `android-release.yml`）。
3. CI 通过后，在 MiTV4A 真机上：
   - 安装 addon，配置保持「stream 开 + 加密 URL 开 + **解密代理关**」（VPS 零视频字节）；
   - 播放一个 h5e 网页源，确认不再花屏；
   - 反复切集验证 §4.3 的 TLS 提示项无崩溃。

**再次确认：本次所有改动均未提交、未推送。**
