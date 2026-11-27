# NuvioTV 移植至小米电视 4A（Android 6.0.1 / API 23）技术报告

## 1. 概述

本项目将开源媒体客户端 NuvioTV（上游 `NuvioMedia/NuvioTV`，工作 fork `fqw000/NuvioTV`，分支 `mitv4a-porting`）从官方要求的 Android 7.0+（minSdk 24）降级移植到 Android 6.0.1（API 23），目标设备为小米电视 4A。移植涵盖构建配置、Java 8 默认方法脱糖、TLS 信任链、插件 JavaScript 运行时（QuickJS）、TMDB 网络连通性、元数据路由、遥控器交互等多个子系统。

截至本报告撰写，fork 上有两个分支：

| 分支 | 头提交 | CI run | 版本 | 状态 |
|---|---|---|---|---|
| `mitv4a-porting`（稳定） | `6a633843b` | #18 | `1.2.0-beta.4` / versionCode 1081 | 设备端日常使用，已验证通过 |
| `mitv4a-upgrade`（升级） | `afdc42ccd` | #19 | `1.2.0-beta.4` / versionCode 1083 | 已合并上游 dev，编译通过，待真机长期验证 |

设备端已安装运行、流解析与播放均验证通过。版本名两分支相同是因为上游 `77cc0bb5f` 与我们 `730589bd2` 各自独立把 beta.3 提到 beta.4，合并时无冲突；versionCode 取上游较高值 1083。

## 2. 目标与范围

- **目标**：使 NuvioTV 在小米电视 4A 上可安装、可运行，插件（CloudStream provider）JS 运行时正常，TMDB 元数据与海报正常加载，视频流可解析、可播放。
- **范围**：仅修改构建配置、网络/TLS 层、插件运行时、元数据/流数据层及必要的 UI 交互；不改变产品功能定位。
- **非目标**：不为单个 provider 站点的反爬（Cloudflare、SNI 阻断等）提供通用绕过方案。

## 3. 目标运行环境

| 项 | 值 |
|---|---|
| 设备 | 小米电视 4A |
| 系统 | Android 6.0.1（API 23） |
| CPU / ABI | Amlogic / armeabi-v7a |
| SELinux | Enforcing |
| 权限 | 无 root |
| 调试 | `adb connect t.v:5555`（网络 ADB） |

## 4. 移植难点总览

| 难点 | 根因 | 解决方案 |
|---|---|---|
| 最低 API 不匹配 | 上游 minSdk 24，部分 AAR 硬编码 minSdk 24 | minSdk 23 + 覆盖 AAR 的 minSdk 版本 |
| 播放器 `AbstractMethodError` | Media3 Java 8 默认接口方法在 API 23 上未被脱糖 | 改用 R8 release 构建注入 default-method bridge |
| 海报/TLS 握手失败 | API 23 不支持 network-security-config 的 `<trust-anchors>` | 运行时 `X509TrustManager` + 内置 ISRG 根证书 |
| 插件“0 流/超时” | QuickJS 无事件循环，`setTimeout` 同步阻塞、`fetch` 同步阻塞 | 真异步 fetch + 真取消 abort + 真异步 setTimeout |
| 插件缺失 JS 全局 | QuickJS 无 `process`/`TextEncoder`/`TextDecoder` | native 辅助 + JS polyfill |
| TMDB 被网络阻断 | `api.themoviedb.org` 在用户网络不可达 | 运行时 TMDB 代理 base URL + host 重写拦截器 |
| 元数据路由错误 | 部分 addon 只支持特定 ID 前缀 | 按 `supportsMetaId(id)` 优先路由 + 负缓存 |
| 流直链弹窗误触发 | 长按确认键的 key-up 残留触发按钮点击 | 去掉关闭按钮，焦点落容器，`onDismissRequest` |

## 5. 关键改动详解

### 5.1 minSdk 24 → 23 与依赖覆盖

- `app/build.gradle.kts`：`minSdk = 23`、`targetSdk = 36`，开启 core library desugaring，启用 ABI splits（universal + armeabi-v7a）。
- 对硬编码 minSdk 24 的 AAR 覆盖版本：`is.xyz.mpv`（mpv-android-lib）及其余 minSdk-24 依赖。
- 修正 Supabase 示例密钥占位。

### 5.2 API 23 默认方法脱糖（`AbstractMethodError`）

- **现象**：API 23 设备播放时报 `AbstractMethodError`，栈指向 `Player.Listener.onEvents`、`SimpleBitmapLoader.loadBitmapFromMetadata` 等接口默认方法。
- **根因**：D8 仅在 `minSdk < 24` 时才脱糖 Java 8 默认方法；debug 构建保留原始 `invoke-interface`，API 23 的 ART 找不到实现即抛错。R8 release 构建会注入 default-method bridge。
- **修复**：CI 固定构建 `assembleFullRelease`（R8）。期间曾排查到重复 Maven media3 模块导致脱糖异常（先排除、后切换 release 后回退该排除）。
- 相关提交：`598d792512`、`eab6cb2aca`。

### 5.3 TLS 信任链与网络

- API 23 不解析 network-security-config 的 `<trust-anchors>`（该特性自 API 24 生效），需运行时方案。
- 新增 `TlsSupport`：自定义 `X509TrustManager`，内置 ISRG Root YR/YE 证书，合并“系统 + 内置”锚点；`init` 必须在 `super.onCreate`（Hilt DI）之前执行。
- Media3 1.8.0 的 `SimpleBitmapLoader` 走系统 `HttpURLConnection` 信任，故另实现 `TlsBitmapLoader` 并通过 `MediaSession.Builder.setBitmapLoader` 注入，解决 `p.lzp261118.com` 海报的 “Trust anchor not found”。
- OkHttp 客户端统一 `TlsSupport.attach`。
- 相关提交：`ab543e13ec`、`5a9a44408a`。

### 5.4 插件运行时（QuickJS）

插件（CloudStream provider）为纯 JS，运行于 QuickJS（`io.github.dokar3:quickjs-kt`，fork AAR `quickjs-kt-android-1.0.5-nuvio.aar`）。QuickJS 无事件循环，`fetch`/`setTimeout`/`process`/`TextEncoder` 等均需由宿主补齐。

#### 5.4.1 fetch 桥接：真异步 + 真取消

- 原始实现为同步绑定 `function("__native_fetch")` + `call.execute()`，阻塞 JS 引擎线程；JS 侧 `fetch` 虽为 `async` 但未 `await`，实为“假异步”；`AbortController` 为不接触 native 的空壳。
- 改为：
  - `asyncFunction("__native_fetch")`（suspend 绑定，返回真 Promise）；
  - `performNativeFetchAsync` 用 `suspendCancellableCoroutine` + `call.enqueue()`，请求在 OkHttp 线程池执行，引擎线程让出；
  - JS 侧 `await __native_fetch(..., abortId)`；
  - `__abort_fetch` 通过 `abortableCalls.remove(id)?.cancel()` 真正取消 OkHttp `Call`，`signal.addEventListener('abort', …)` 透传；
  - `CancellationException` 单独捕获并 `call.cancel()` 后 `rethrow`，不吞取消。
- 相关提交：`4d87759bc1`（真异步 fetch + 真 abort）。

#### 5.4.2 setTimeout：真异步调度器

- 原始实现为 `function("__native_sleep")` + `Thread.sleep(ms)` 同步阻塞。`new Promise(executor)` 的 executor 同步执行，导致 `fetchWithTimeout` 的 `Promise.race([fetch, timeout])` 顺序反转：先睡满 20s 并 reject，`fetch` 才发出，成功响应被 race 判超时丢弃——这是“provider 在 iOS/Mac 正常、在 TV 端 0 流/恒定慢”的最终根因。
- 改为：
  - 新增 `asyncFunction("__native_delay")`，用 `kotlinx.coroutines.delay(ms)` 真挂起；
  - JS `setTimeout(fn, ms)` 启动 async 任务：分段 `await __native_delay(step)`（≤100ms 粒度）轮询后执行回调，立即返回 timer id；
  - `clearTimeout`/`clearInterval` 真正生效（置 `active=false` 并删除条目，轮询在 ≤100ms 内退出）；
  - `setInterval` 因无事件循环仍退化为一次性调用。
- 相关提交：`730589bd2b`（真异步 setTimeout + clearTimeout，版本 bump 1.2.0-beta.4）。

#### 5.4.3 JS 全局 polyfill 与 TMDB URL 重写

- 补 `process`（env/platform/nextTick 等）、`TextEncoder`/`TextDecoder`（经 native `__text_encode`/`__text_decode` 做 UTF-8/base64）。
- fetch 桥接内 `rewriteTmdbUrl`：将 `api.themoviedb.org` 重写到运行时配置的 TMDB 代理 host。
- 相关提交：`f0463f8272`、`d67c247c94`、`5a9a44408a`（setTimeout 初版 polyfill）。

### 5.5 TMDB 连通性与运行时代理

- 现象：用户网络下 `api.themoviedb.org`（解析到 `108.160.167.165`）请求失败；`https://api.tmdb.org/3/`（别名，经 CloudFront 重定向到 `api.themoviedb.org`）返回 200。
- 方案：
  - `TmdbApiConfig`：`@Volatile overrideBaseUrl`、`effectiveBaseUrl`、健壮 `host` 解析（`parseHost` 拒绝 `https`/`http`/空串/`:`/`/`，异常回退默认值）；
  - `TmdbHostRewriteInterceptor`：把 host `api.themoviedb.org` 重写为目标 host，保留 `/3/` 路径；
  - `NetworkModule.provideTmdbRetrofit` 固定 baseUrl `https://api.themoviedb.org/3/` + 拦截器；
  - 设置页 `TmdbProxyUrlDialog` 提供运行时 TMDB 代理 URL 录入（DataStore 键 `tmdb_api_base_url`）。
- 相关提交：`4d87759bc1`（运行时设置）、`f0463f8272`（host 解析健壮化）。

### 5.6 元数据路由与负缓存

- `MetaRepositoryImpl.selectPrimaryMetaCandidate` 改为先按 `supportsMetaId(id)` 路由，再考虑 requestedType/inferredType；新增 `primaryMetaNegativeCache`（TTL 2 分钟），失败写入负缓存，避免每次重复查询不可用的 primary meta addon。
- 相关提交：`4d87759bc1`。

### 5.7 Watch Next 门槛

- Android TV 的 Watch Next / playback state 接口自 API 26 才完整可用，API 23 调用会崩溃。
- `TvRecommendationManager` 新增 `isWatchNextSupported()`（`SDK_INT >= O`），对 `updateWatchNext`/`updateSingleWatchNextProgram`/`flushPendingWatchNext`/`onProgressRemoved` 统一加门槛。
- 相关提交：`4d87759bc1`。

### 5.8 应用内升级禁用

- 上游 release 目标 minSdk 24，与本 API 23 移植不兼容，故关闭应用内升级。
- `AppFeaturePolicy.inAppUpdatesEnabled = false`；`UpdateViewModel.checkForUpdates`/init/`syncBackgroundJobs` 与 `UpdateJobService.checkAndDownload`/`schedulePeriodicCheck`/`scheduleCheckNow` 全部加 `!AppFeaturePolicy.inAppUpdatesEnabled` 门槛。
- 相关提交：`f0463f8272`。

### 5.9 独立 ID provider 触发（#15）

- **现象**：`tt` ID 正常出流；`jp` ID 在 TV 端红色退出、provider 零执行；同一 provider 在 iOS/Mac 客户端正常。
- **根因**：`StreamRepositoryImpl.canRunLocalPlugins()` 白名单只放行 `kitsu:` / `anilist:` / `mal:` 前缀，独立 ID（`jp148390`、`trybox_980431`）在调用 provider 之前就被客户端静默丢弃。iOS/Mac 是独立 KMP 客户端（NuvioMobile/NuvioDesktop），其 `pluginContentId()` 无此白名单，所以正常。
- **证据链**：TV logcat 出现 `jp... -> TMDB ID: null` / `Unknown video ID format`，而 provider 侧自建日志（`pv.wangqifei.eu.org/api/logs`）零回传 → 证明 JS 引擎根本没跑。历史检索也确认唯一 jp 成功触发 provider 的记录来自 iOS。
- **修复**（`StreamRepositoryImpl.kt`，提交 `65194e1a75`）：
  - `canRunLocalPlugins()` 白名单改为 `isNotBlank()`，任何非空 ID 都透传给 provider，由 provider 自行识别并 early-return；
  - `cleanKitsuPluginId()` → `cleanPluginId()`：番剧 absolute ID 保留 `kitsu:6448` 形式，其他 ID 用正则 `:\d+:\d+$` 仅剥末尾 Season/Episode；
  - 新增 `resolveSeasonEpisodeFromId()`：当 UI 未提供季集时，从 ID 尾部兜底解析 `:S:E`（如 `jp148390:1:1`），保证 provider 拿到正确集数。
- **注**：此修复在 `StreamRepositoryImpl.kt`，不涉及 `PluginManager.executeScrapersStreaming()`。

### 5.10 流直链复制弹窗交互（#16 增、#17/#18 修）

- 新增：菜单键/长按显示流直链并复制（`b286ef38f3`）。
- #17 `b246d6c17`：打开即自动复制直链并弹 toast，只保留一个全宽居中、自动聚焦的关闭按钮；URL 上方显示“链接已复制到剪贴板”（无直链则显示“没有可用的直链”）；代码注释明确“不要再加吞 `ACTION_UP` 的 handler”。
- #18 `6a633843b`：去掉关闭按钮，弹窗只显示“标题 + 已复制提示 + URL + 按返回键关闭”，返回键走 `Dialog.onDismissRequest`；焦点落弹窗容器本身，避免长按确认键的残留 key-up 误触发点击、也防止焦点穿透到后方流卡片。回调链 `StreamCard → StreamsList → RightStreamSection → StreamScreen`，静态校验弹窗内 `Card(` = 0、`onPreviewKeyEvent` = 0、自动复制 1 处。

### 5.11 上游同步（#19，新建 `mitv4a-upgrade` 分支）

- **不改动稳定分支**：从 `mitv4a-porting`（`6a633843b`）切出 `mitv4a-upgrade`，合并上游 dev `9ce2f22b6`（距分叉点 `d2eeb3d0e` 共 7529 个提交），7 个冲突全部手动解决，原则是“移植侧 + 上游侧都保留”：
  1. `pr-full-debug-build.yml`：保留 `CI_USE_DEBUG_SIGNING=true` 的 fullRelease 构建（产物上传依赖它）+ 叠加上游 Usenet 检查/测试；
  2. `app/build.gradle.kts`：versionCode 1081→1083（取上游），minSdk 23 保留；
  3. `NuvioApplication.kt`：保留 `TlsSupport`，采用上游 image client 重构（NetworkFetcher.Factory + CollectionCoverNetworkClient）；
  4. `MatroskaChapterLoader.kt`：TLS attach 叠加 IPv4FirstDns + 6s 超时；
  5. `EmbeddedSubtitleTimelineLoader.kt`：采用上游共享 `playbackHttpClient`（已继承 TlsSupport）；
  6. `StreamScreen.kt`：流链接复制弹窗 + 上游 `RewatchPromptOverlay` 两者都保留；
  7. `strings.xml`：`stream_url_*` 与 `player_disc_*` 两组都保留。
- **功能逐项核对全部保留**：TLS 根证书、minSdk 23 + desugaring、TMDB 代理、ID 放行、流链接复制、升级屏蔽、真异步运行时。上游新增大量 `java.time.*` 调用（API 26+），但项目已有 `coreLibraryDesugaring`（2.1.4），API 23 可运行。
- 已提交 `afdc42ccd` 并推送 `fork/mitv4a-upgrade`；CI run `45954914516` 编译成功（30m50s），产物 armeabi-v7a 69.1 MB / universal 203 MB。**后续用户决定：上游只有大更新才再移植，小更新不动。**

### 5.12 客户端与 provider 双侧的超时竞态修复（`nuvioProvider` 仓库）

排查中同步修改了 addon 侧 JS（仓库 `fqw000/nuvioProvider` 的 `main` 分支，提交 `d27d1b0`、`34d865b`），与客户端运行时改动互为补充：

- `d27d1b0`：移除 `fetch` 的 `signal`（TV QuickJS 的空壳 abort 会把 12s/20s 熔断误判为 `AbortError`；jpyy 两次 20s 重试会撞上客户端 60s 硬超时，连日志回传都被取消），仅保留 `Promise.race` 超时兜底并补 `clearTimeout`；jpyy `MAX_RETRIES` 2→1。本地验证 `node --check`、mock 51/51、e2e 21/21。
- `34d865b`：jpyy 的 TMDB_API_BASE 从 `api.tmdb.org`（tv 端解析到 IPv6 2600:9000::，握手 20s 超时）切到自建代理 `tmdb.demojameson.de5.net/3/`，两域名返回结构已逐字段比对一致（91780B / 30 字段）。
- 该仓库 `origin/main` 即上述两个提交，已部署。

## 6. CI/CD 与构建产物

- 工作流 `.github/workflows/pr-full-debug-build.yml`：`workflow_dispatch` + PR（dev）触发。
- 步骤：解码 `LOCAL_PROPERTIES_BASE64`/`LOCAL_DEV_PROPERTIES_BASE64` → 生成 CI 调试 keystore → `CI_USE_DEBUG_SIGNING=true ./gradlew :app:assembleFullRelease --stacktrace`。
- 产物：`app-full-universal-release.apk`、`app-full-armeabi-v7a-release.apk`（保留 7 天）。
- 小米电视 4A 使用 **armeabi-v7a** 包。
- CI 调试 keystore 每次运行重新生成 → 每次安装前必须 `adb uninstall com.nuvio.tv`。
- **体积变化**：#18 armeabi-v7a 63.4 MB → #19 69.1 MB；拆解 APK 确认增量 100% 来自上游新引入的 `libnuvio_usenet.so`（raw 11.44 MB / 包内 4.77 MB），dex/res/assets 几乎不变。APK 本身已充分压缩（R8 minify + shrinkResources、`.so` 已 strip、`useLegacyPackaging=true`），进一步瘦身只能砍功能；用户决定暂不实施。

## 7. 提交历史

基线（上游 dev）：`d2eeb3d0` fix(android): mark wifi as optional

### 7.1 `mitv4a-porting`（稳定分支，自基线起）

| 提交 | 说明 |
|---|---|
| `4e3b8ddd` | feat: Android 6.0.1（API 23）支持 + 现代 HTTPS 根 |
| `4898899b` | fix: 覆盖 minSdk 24 AAR + 修正 Supabase 示例密钥 |
| `c849ff8e` | fix: 覆盖 mpv-android-lib 的 minSdk 24 |
| `df1bf89b` | ci: 追加上传 armeabi-v7a split APK |
| `598d7925` | fix: 排除重复 Maven media3 模块（修默认方法脱糖） |
| `eab6cb2a` | ci: 改用 R8 release 构建脱糖 Media3 默认方法；回退 media3 排除 |
| `ab543e13` | tls: 内置 ISRG Root YR/YE，合并系统+内置锚点，Hilt 前 init |
| `5a9a4440` | fix: TLS-aware 海报加载器、JS setTimeout polyfill、可配置 TMDB base URL |
| `d67c247c` | plugin: fetch 桥接重写 `api.themoviedb.org` |
| `4d87759b` | fix: 真异步 fetch + 真 abort、TMDB 运行时代理、primary-meta 路由+负缓存、watch_next 门槛 |
| `f0463f82` | TMDB 代理 host 健壮解析、禁用应用内升级、process/TextEncoder/TextDecoder polyfill |
| `730589bd` | plugin: 真异步 setTimeout + clearTimeout 调度器；bump 1.2.0-beta.4 |
| `65194e1a` | fix(plugin): 向本地插件传 standalone ID（jp/trybox） |
| `b286ef38` | feat(stream): 菜单键/长按显示并复制流直链 |
| `b246d6c1` | fix(stream): 打开即自动复制、修复按钮不可点击 |
| `6a633843` | fix(stream): 去掉关闭按钮，消除长按 key-up 误关闭 |

### 7.2 `mitv4a-upgrade`（升级分支）

| 提交 | 说明 |
|---|---|
| `afdc42ccd` | Merge upstream NuvioTV dev (`9ce2f22b6`) into mitv4a-upgrade，7 冲突全保留双侧改动 |

### 7.3 `nuvioProvider`（addon 侧）

| 提交 | 说明 |
|---|---|
| `d27d1b0` | fix(provider): 移除 fetch signal，Promise.race 超时兜底 + clearTimeout，jpyy MAX_RETRIES 2→1 |
| `34d865b` | fix(jpyy): TMDB 域名切到自建代理，修复电视端 40s 超时 |

## 8. 安装与验证

```bash
/Users/wangqifei/Documents/platform-tools/adb connect t.v:5555
/Users/wangqifei/Documents/platform-tools/adb uninstall com.nuvio.tv   # CI 调试签名每次不同，必须卸载
/Users/wangqifei/Documents/platform-tools/adb install -r v7a_v14.apk
```

验证手段：
- DEX 静态校验：检查 `__native_fetch`、`__abort_fetch`、`__native_delay`、`__nuvio_timer_seq`、`TextEncoder`、`globalThis.process`、`tmdb_api_base_url` 等标记是否进入产物。
- 设备 logcat：`PluginRuntime` 的 fetch 结果、`PluginManager` 的 scraper 结果、错误栈。
- provider 侧日志回传（用户自建 `pv.wangqifei.eu.org`）用于定位 `fetchWithTimeout` 超时反转。

## 9. 验证证据链

### 9.1 tt / jp 测试矩阵（清空日志后按电影/剧集四象限重测）

| ID 类型 | 内容 | iOS/Mac | MiTV4A（修复前） | MiTV4A（修复后） |
|---|---|---|---|---|
| `tt` | 电影 / 剧集 | 正常出流 | 正常出流 | 正常出流 |
| `jp` | 电影 / 剧集 | 正常出流 | **红色退出，provider 零执行** | 正常触发 provider |

判定依据：TV 端 logcat 有 `Unknown video ID format` / `TMDB ID: null`，provider 侧自建日志零回传，说明客户端白名单在 JS 执行前就拦截；修复后 provider 日志正常回传、流可播放。

### 9.2 CI 编译验证（本机无 Android SDK，全部依赖 GitHub Actions）

| run | 分支 | 结果 | 对应改动 |
|---|---|---|---|
| `45936166134` | mitv4a-porting | success | ID 白名单修复 |
| `45943433929` | mitv4a-porting | success | 流链接弹窗首版 |
| `45947077704` | mitv4a-porting | success | 打开即复制版 |
| `45949948049` | mitv4a-porting | success | 无按钮版（#18，稳定） |
| `45954914516` | mitv4a-upgrade | success | 上游合并（#19） |

### 9.3 静态校验

- DEX 中确认 `__native_fetch`、`__abort_fetch`、`__native_delay`、`__nuvio_timer_seq`、`TextEncoder`、`globalThis.process`、`tmdb_api_base_url` 等标记存在。
- 流链接弹窗源码：弹窗内 `Card(` = 0、`onPreviewKeyEvent` = 0、自动复制回调 1 处。

## 10. 是否向上游提交 PR 的评估（结论：暂不提交）

上游 `CONTRIBUTING.md` 规则严格：大型改动、新功能、UI/架构变更必须先有 maintainer 明确批准的 feature request，否则 PR「won't be reviewed at all and will be closed immediately」。本移植 50 文件 / 1238 行整体提交必然被拒。分类评估：

| 改动 | 可行性 |
|---|---|
| `65194e1a7` ID 白名单修复 | 唯一有希望：属于可复现行为 bug，有完整 logcat 证据链，符合「reproducible bug fix」，需先开 bug issue |
| PluginRuntime 真异步 / polyfill | 涉及运行时架构，需 feature request 获批 |
| 流链接复制弹窗 | 新功能 + UI 改动，需 feature request + 前后截图 |
| minSdk 23 / TLS 根证书 / TMDB 代理 / 禁用更新 / CI 改动 | MiTV4A 设备与网络环境特定，不建议提交 |

**用户决定**：暂不提 PR，维持现状；后续只有上游大更新才再移植。

## 11. 遗留问题与限制

1. **部分 provider 仍失败**，属环境/站点侧而非运行时：DNS 阻断（watch32.sx、backend.vidnest.fun、yflix）、Cloudflare 挑战（kisskh、vixsrc.to）、SNI/TLS 重置（cinevibe、castle、YouTube）、站点变更（moviesdrive、mapple/anilist 404）。
2. **QuickJS 不支持 class 私有字段**：`SyntaxError: invalid first character of private name`，使用 `#x` 的 provider 直接解析失败；需换引擎或语法降级。
3. **`evalAndAwait` 尾部等待**：若 provider 的 `fetchWithTimeout` 未在 `finally` 中 `clearTimeout(timer)`，结果正确但整体评估会等到超时计时器自然走完才返回；建议 provider 补 `finally(clearTimeout)`。
4. **`setInterval` 非真周期**：QuickJS 无事件循环，退化为一次性延迟调用。
5. **TryBoxIPCheck 服务端鉴权**：`Supabase HTTP 400`（服务端 auth），需服务端配合。
6. **良性告警**：`TmdbService: Unknown video ID format: trybox_*/dy*`，不影响主流程。
7. **升级分支未做真机长期验证**：`mitv4a-upgrade` 仅完成编译与产物校验，日常使用仍建议留在 `mitv4a-porting`。
8. **包体积**：#19 比 #18 大 6 MB（新增 Usenet so），暂不瘦身；安装 CI 包前必须卸载旧包（调试签名每次不同）。

## 12. 结论

本次移植已将 NuvioTV 从 minSdk 24 成功降级至 API 23 并适配小米电视 4A，核心的构建、TLS、QuickJS 插件运行时（真异步 fetch/abort/setTimeout）、TMDB 连通、元数据路由、独立 ID provider 触发与遥控器交互均已闭环，设备端运行与播放验证通过。剩余失败主要集中于个别 provider 站点的反爬/网络阻断与 QuickJS 的语法能力边界，属于可继续增量治理的范畴，不影响整体移植结论。

当前主线仍是稳定的 `mitv4a-porting`（`6a633843b`）；`mitv4a-upgrade`（`afdc42ccd`）已完成上游 dev 合并并编译通过，作为后续上游大更新时的升级基线保留。
