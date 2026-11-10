# 03 · 关键类与函数说明

本文按层列出核心类及其关键字段/方法。函数签名以源码为准（省略部分修饰符），用途说明侧重“为什么”和边界条件。

---

## 1. 入口层

### 1.1 `MainActivity`（TV 入口 / 调度器）

源码：[MainActivity.java](../../app/src/main/java/family/libretv/tv/MainActivity.java)（约 1030 行）

| 成员 | 说明 |
| --- | --- |
| `enum Screen` | `CONFIG/LOGIN/SEARCH/FAVORITES/PLAYLIST/SETTINGS/PAIRING/DETAIL/PLAYER` |
| `ExecutorService network, updateChecks, searches` | 单线程执行器，分别承载普通网络、更新检查、搜索 |
| `NasClient client` | 内容访问门面（单机模式） |
| `Player player` | 当前 ExoPlayer 实例 |
| `int generation` | 异步任务票号，防止过期回调 |
| `onCreate(Bundle)` | 判断是否电视设备；非电视跳 `PhoneActivity`；无 `tvDestination` 跳 `TvHomeActivity`；否则初始化界面 |
| `<T> void task(String hint, Work<T> work, Done<T> done)` | 后台执行 + 主线程回调 + 票号校验 + 错误提示（`Work/Done` 为内部函数式接口） |
| `void showScreen(Screen screen, ...)` | 切换 TV 页面并重建内容视图 |
| `void performSearch(String keyword)` | 调 `client.local.search(keyword, selected, listener)` 增量渲染 |
| `void loadDetail(JSONObject item, JSONObject source)` | 请求 `/api/detail`，解析选集 |
| `void preparePlayback(JSONObject detail, int index)` | 计算播放地址、初始化播放器与片头片尾控制器 |
| `void showPlayer(...)` | 构建 `ExoPlayer` + `DefaultMediaSourceFactory(() -> new NasDataSource(client))` |
| `boolean dispatchKeyEvent(KeyEvent)` | 遥控：左右快退/快进 30s，长按 60s；播放页返回收起控制栏 |
| `void onStop()` | 释放播放器、海报加载器 |

### 1.2 `PhoneActivity`（手机主界面，Kotlin）

源码：[PhoneActivity.kt](../../app/src/main/java/family/libretv/tv/PhoneActivity.kt)（约 1230 行）

| 成员 | 说明 |
| --- | --- |
| `screen(name: String, heading: String)` | 统一页面导航；竖屏底部五入口，横屏左侧栏 |
| `work(hint: String) { ... }` | 协程封装：显示加载态、捕获异常、回主线程 |
| `startSearch(keyword)` | `runInterruptible` 调用 `LocalRepository.search`，增量渲染并支持取消 |
| `showDetail(item, source)` | 详情页：海报、简介、选集、收藏/片单/投屏入口 |
| `openLive()` / `openLiveSources()` | 直播频道页与 M3U 订阅管理 |
| `openControls()` | 投屏控制页：设备、当前内容、音量、进度（3 秒轮询） |
| `batchScreen(queue)` | 批量投屏页，调用 `PhoneCastBatch` |
| `onDestroy()` | `DLNACast.cleanup()` |

### 1.3 `TvHomeActivity`（TV 首页）

源码：[TvHomeActivity.java](../../app/src/main/java/family/libretv/tv/TvHomeActivity.java)（约 143 行）

| 成员 | 说明 |
| --- | --- |
| `onCreate` | 构建 hero（继续观看）+ 快捷入口 + 推荐分类 |
| `renderHistory()` | 1 张主卡 + 侧栏 + 4 列历史海报 |
| `renderRecommendations()` | 分类 `movie/tv/show/anime/top` |
| `wireHomeFocus()` | 显式 `nextFocus*` 链，保证遥控可达 |
| `launch(String destination)` | 带 `tvDestination` 跳转 `MainActivity`（`favorites/playlist/settings/search/resume`） |

---

## 2. 页面与播放层

### 2.1 `DirectPlayerActivity`（手机点播）

源码：[DirectPlayerActivity.java](../../app/src/main/java/family/libretv/tv/DirectPlayerActivity.java)

| 成员 | 说明 |
| --- | --- |
| `onCreate` | 解析 Intent（源、条目、集数），按方向构建竖屏详情或横屏全屏 |
| `bindPlayer()` | 创建 `ExoPlayer`、`PlayerView`，挂载手势层、字幕菜单、片头片尾控制器 |
| `renderEpisodes()` | 选集网格；切换集时保存旧进度 |
| `toggleFavorite()` | 调 `LocalRepository.favorites` 增删 |
| `onStop()` | `PlaybackStore.save(...)` 写入进度后释放播放器 |

### 2.2 `LivePlayerActivity`（直播播放）

源码：[LivePlayerActivity.java](../../app/src/main/java/family/libretv/tv/LivePlayerActivity.java)

| 成员 | 说明 |
| --- | --- |
| `onCreate` | 读取频道与线路，构建 HLS 播放器与看门狗 |
| `beginRound(int firstLine)` | 新一轮恢复预算：`LiveRecoveryPolicy.begin(lines, first)` |
| `startAttempt()` | 按当前线路创建媒体源，注入 UA/Referer 请求头 |
| `fail(Throwable)` / `classify(Throwable)` | 分类：落后直播窗口、解码错误、HTTP 状态、超时/DNS/SSL |
| `samplePlayback()` | 周期性调用 `LivePlaybackWatchdog.sample(...)` |
| `switchChannel(int delta)` | 隐藏控制栏时左右键静默切台 |
| `showCategoryPanel()` | 频道分类/选台覆盖层 |

播放器参数：`DefaultHttpDataSource`（UA/Referer）、`DefaultLoadErrorHandlingPolicy(1)`、`DefaultLoadControl(10000/25000/1500/4000)`、`liveTargetOffsetMs=5000`。

### 2.3 `LiveBrowseActivity`（TV 直播浏览）

源码：[LiveBrowseActivity.java](../../app/src/main/java/family/libretv/tv/LiveBrowseActivity.java)

| 成员 | 说明 |
| --- | --- |
| `onCreate` | `RecyclerView + GridLayoutManager`（2–5 列），左侧分组栏 |
| `renderChannels()` | 收藏优先 + 分组过滤 |
| `openChannel(JSONObject)` | 跳 `LivePlayerActivity` |
| `importPlaylist()` | `ACTION_OPEN_DOCUMENT`，失败回退 `ACTION_GET_CONTENT`，交给 `LivePlaylistFile` |
| `manageSubscriptions()` | 订阅启用/删除/刷新对话框 |

---

## 3. 领域与数据层

### 3.1 `LocalRepository`（单机 CMS 核心）

源码：[LocalRepository.java](../../app/src/main/java/family/libretv/tv/LocalRepository.java)

构造时使用 `SharedPreferences("standalone-library")`。

| 方法 | 说明 |
| --- | --- |
| `void mergeDefaults(SharedPreferences, JSONArray catalog)` | 合并打包默认源；`knownDefaultUrls` 记录已删除的默认源（上限 50），避免被再次加回 |
| `JSONArray sources()` | 读取启用/停用的全部来源 |
| `void saveSources(JSONArray)` / `removeSource(String key)` / `setSourceEnabled(String key, boolean)` | 来源 CRUD |
| `String exportSources()` | 导出规范化 JSON |
| `int importSources(String input)` | 支持 URL 拉取或直接 JSON；最多 50 条；过滤成人源 |
| `static JSONArray parseSources(String text, boolean allowEmpty)` | 兼容三种结构：`{sources:[...]}`、顶层数组、TVBox `sites`（仅 `type=1`）、单对象 |
| `static URI validate(String url)` | 仅 http(s)，禁止 userinfo/fragment |
| `static HttpURLConnection open(URI)` | 统一 UA，连接 6s / 读取 8s |
| `static String api(String root, String action, String value)` | 拼接 `?ac=videolist&wd=...` 或 `&ids=...` |
| `JSONObject search(String wd, JSONArray selected, SearchListener listener)` | 并发搜索（≤4 线程、22s 截止、每源 ≤5 页、去重、相关性过滤） |
| `static JSONArray episodes(String play)` | 解析 `$$$`/`#`/`$` 分隔的播放串，优先 m3u8 分组 |
| `JSONObject detail(String id, JSONObject source)` | `ids` 接口取详情；可选抓详情 HTML 正则补 m3u8 |
| `JSONObject favorites(String route, JSONObject body, String method)` | 收藏 GET/POST/DELETE；ID = `SHA-256(url + ":" + vodId)` 前 16 位 |
| `static String key(String url)` | 来源 key（SHA-256 前 16 位） |
| `static String normalize(String)` | 去空白/标点/符号并小写，用于相关性比较 |

### 3.2 `NasClient`（统一访问门面）

源码：[NasClient.java](../../app/src/main/java/family/libretv/tv/NasClient.java)

| 方法 | 说明 |
| --- | --- |
| `NasClient(Context)` | 单机模式：`origin=https://standalone.invalid/`，`local=new LocalRepository(context)` |
| `NasClient(URI origin)` | NAS 模式：`local=null`，维护 Cookie 会话 |
| `JSONObject request(String path, JSONObject body)` / `(path, body, method)` | 单机模式分发给 `LocalRepository`；NAS 模式发起 HTTP |
| `HttpURLConnection open(URI)` | 构造连接，注入 Cookie（NAS）或统一 UA（单机） |
| `URI proxy(String upstream)` | 生成 NAS `/api/proxy?url=...` 地址 |
| `String playbackMime(URI proxy, String upstream)` | 探测播放 MIME |
| `JSONArray sources(JSONObject status)` | 返回源列表；NAS 模式还处理 `defaultSources/defaultSubscriptions` |

### 3.3 `NasDataSource`（Media3 数据源）

源码：[NasDataSource.java](../../app/src/main/java/family/libretv/tv/NasDataSource.java)

| 方法 | 说明 |
| --- | --- |
| `long open(DataSpec)` | 访问控制 → 设置 `Range` → 校验 2xx → 处理 200 忽略 Range 时手动 skip → 计算剩余长度 → 传输回调 |
| `int read(byte[], int, int)` | 按 `remaining` 限长；提前 EOF 抛 `EOFException` |
| `void close()` | 关闭流、断开连接、`transferEnded()` |

访问控制：
- 单机模式（`client.local != null`）：允许任意 `http(s)`。
- NAS 模式：必须同源且路径为 `/api/proxy` 或其子路径，否则抛 `IOException("已阻止非 NAS 代理的播放请求")`。

### 3.4 `PlaybackStore`（历史 / 片单）

源码：[PlaybackStore.java](../../app/src/main/java/family/libretv/tv/PlaybackStore.java)

`SharedPreferences("standalone-playback")`。

| 方法 | 说明 |
| --- | --- |
| `static String identity(JSONObject source, JSONObject item)` | `source.url`（去尾部 `/`）+ `"|"` + `vodId` |
| `JSONObject find(source, item)` / `boolean contains(...)` | 查询记录 |
| `void add(source, item, title)` | 加入片单（上限 200） |
| `void remove(String id)` | 从片单移除 |
| `void save(source, item, title, index, position, duration)` | 写历史（上限 50），同步更新片单同 ID 进度 |
| `void clear()` | 清空历史 |
| `static long resumePosition(long position, long duration)` | 距结尾 `min(15s, duration/20)` 内视为看完，归零 |

### 3.5 `LiveRepository`（直播订阅与线路）

源码：[LiveRepository.java](../../app/src/main/java/family/libretv/tv/LiveRepository.java)

`SharedPreferences("live-library")`；`key` 使用 SHA-256 前 16 位。

| 方法 | 说明 |
| --- | --- |
| `String saveSubscription(String id, String name, String url)` | 新增/更新订阅（按 URL 去重） |
| `JSONArray subscriptions()` / `void enable(id, boolean)` / `void remove(id)` | 订阅管理；删除时清理缓存与状态键 |
| `LiveRepository.Refresh refresh(boolean force)` | 按 `due()` 拉取 M3U 并重建频道缓存；默认 6h，可配置；失败保留旧缓存并记录错误 |
| `JSONArray channels()` | 合并所有启用订阅的频道缓存 |
| `JSONObject channel(String id)` | 按 ID 查询频道 |
| `boolean favorite(String id)` / `void toggleFavorite(String id)` | 收藏 |
| `void remember(String id)` / `String lastChannel()` | 上次播放频道 |
| `int preferredLine(JSONObject channel, long now)` | 选择健康线路（跳过冷却中的线路） |
| `void markLineFailed(JSONObject channel, int index, long now)` | 线路失败，冷却 10 分钟 |
| `void markLineStable(JSONObject channel, int index, long startupMs)` | 线路稳定，清除失败状态 |
| `static Map<String,String> headers(JSONObject line)` | 提取 UA / Referer |
| `static String mime(JSONObject line)` | 依据扩展名/`#EXTM3U` 判断 HLS |
| `String importPlaylist(String name, String text)` | 导入本地 M3U 为 `file-<hash>` 订阅 |

频道编码时按 `tvg-id` 或名称去重，把多线路合并到同一频道的 `lines` 数组。

### 3.6 `RecommendationRepository`

源码：[RecommendationRepository.java](../../app/src/main/java/family/libretv/tv/RecommendationRepository.java)

- 数据源：`movie.douban.com/j/search_subjects`。
- `load(category, force)`：分类映射 `movie/tv/show/anime` → 豆瓣 tag；缓存 TTL 6 小时；失败保留旧缓存。
- 校验：`cover` 必须为 http(s)，`rating` 限定 0–10。
- 支持全局开关。

### 3.7 片头片尾三件套

| 类 | 关键成员 |
| --- | --- |
| `SegmentSkipPolicy` | `titleKey(title)` 去空白小写；`valid(intro,outro,duration)` 不重叠且正片 ≥1s；`introTarget(...)`；`inOutro(...)` |
| `SegmentSkipStore` | `intro()`、`outro()`、`enabled()`、`setEnabled()`、`mark(tail, millis)`、`summary()`；key = 剧名 + `:intro/:outro/:enabled` |
| `SegmentSkipController` | `addControls(parent, tv)`、`bind(Player)`、`mark(tail)`、`toggle()`、`isCompleted()`、`isCountingDown()`；250ms tick + 5s 倒计时 |

---

## 4. 网络、协议与更新

### 4.1 `AppUpdater`

源码：[AppUpdater.java](../../app/src/main/java/family/libretv/tv/AppUpdater.java)

常量：`REPOSITORY=github.com/if-lensman/libretv-standalone`、`GITEE_MIRROR`、`DEFAULT_PROXY=ghfast.top`、`SECOND_PROXY=gh-proxy.org`、`MAX_APK=100MB`。

| 成员 | 说明 |
| --- | --- |
| `Release check()` | 按通道获取清单；优先 Gitee Contents API（base64 解码） |
| `void download(Release, File, Progress)` | 下载到 `.part` 再 rename；校验大小与 SHA-256 |
| `static void validateApk(Context, File, int expectedCode)` | 包名、versionCode、签名与已安装应用一致 |
| `static URI validateGithubAsset(String)` / `validateProxy(String)` | 仅允许受信 host/前缀 |
| `private static HttpURLConnection githubConnection(URI, URI proxy)` | 手写重定向（≤6 次），每跳限定 host |
| `static URI mirrorTarget(URI)` | 把 GitHub 资源映射到 Gitee 镜像 |
| `static String hex(byte[])` | 十六进制工具 |

`Release` 字段：`code/name/notes/size/sha256/url/minSdk/packageName`。

### 4.2 更新安装

| 类 | 说明 |
| --- | --- |
| `UpdateInstaller` | API≥24：`ACTION_INSTALL_PACKAGE` + FileProvider；<24：`PackageInstaller` 会话 |
| `UpdateInstallReceiver` | `PENDING_USER_ACTION` 启动系统确认；失败 Toast |
| `UpdateActivity` | 检查/下载/安装 UI；24 小时自动检查一次；恢复待安装包；未知来源授权；兼容安装与保存 APK |

### 4.3 `PhonePairingServer`（局域网导入）

源码：[PhonePairingServer.java](../../app/src/main/java/family/libretv/tv/PhonePairingServer.java)

| 成员 | 说明 |
| --- | --- |
| `ServerSocket`（随机端口） | 仅监听局域网；页面令牌 24 字节随机 |
| 生命周期 | 5 分钟有效，`used` CAS 保证单次提交 |
| 校验 | `Host`/`Origin` 防 DNS rebinding；CSP 限制脚本；请求体 ≤128KB |
| 模式 | 视频来源 JSON / 直播订阅 URL / NAS 地址+密码 |
| 表单 | `SourceImportForm.html(action, nonce)` |

### 4.4 解析与地址

| 类 | 方法 | 说明 |
| --- | --- | --- |
| `M3uPlaylist` | `parse(String)` | 限 4MB / 5000 频道；解析 `EXTINF` 的 `group-title/tvg-id/tvg-logo`、`EXTVLCOPT` 的 UA/Referer；拒绝 HLS 媒体清单 |
| `LivePlaylistFile` | `importInto(Context, LiveRepository, Uri)` | 从文档选择器读取并导入 |
| `NasAddress` | `parse(String)` / `sameOrigin(URI, URI)` | 仅 http(s)，禁凭据/查询/片段/localhost；同源比较含默认端口 |
| `CredentialStore` | `save(origin, secret)` / `load(origin)` | AES-GCM + AndroidKeyStore；AAD=origin；仅保存一个配置 |

---

## 5. 投屏层

| 类 | 关键成员 |
| --- | --- |
| `CastUrl` | 静态校验：仅 http(s)、拒绝 `NasAddress.sameOrigin` 的地址 |
| `PhoneCastQueue`（data class） | `title`、`urls`、`index`、`poster`、`itemJson`、`sourceJson`；`adjacent()`、`at(i)`、`label()` |
| `PhoneCastBatch` | 顺序逐集投递，间隔 1.2s；失败不重试，返回 unconfirmed；`stop()` 可中断 |
| `ReceiverDiscovery` | 并发（信号量 6）拉取 description XML；校验 `AVTransport`；禁 DOCTYPE/ENTITY；限 256KB |

投屏调用链：

```
PhoneActivity → DLNACast.search(timeout)
             → DLNACast.castToDevice(device, url, title, options)
             → CoreManager.connectAndPlay
             → DlnaMediaController.playMediaDirect
             → SOAP SetAVTransportURI + Play
```

---

## 6. 公共 UI 与工具

| 类 | 关键成员 | 说明 |
| --- | --- | --- |
| `PiankeUi` | `surface/text/brand/progress/entry/about` | 品牌色 `#101723`、`#192535`、`#f15b9b`；入口按钮带图标与副标题 |
| `PlayerStyle` | `action/tvAction/background/tvTileBackground/Icon` | 纯 Canvas 24×24 矢量图标；焦点/选中/禁用三态颜色 |
| `PosterLoader` | `load(view, client, url)`、`loadPublic(view, url, placeholder)`、`close()` | 8MB LRU；`view.setTag(key)` 防错位；豆瓣 Referer |
| `PosterCard` | `onFocused`、`image/title/source/metadata` | `FOCUS_BLOCK_DESCENDANTS` 保证单一焦点 |
| `PlaybackGestureLayout` | `bind(player, video, excluded...)`、`setVodSeekGestures(bool)`、`release()` | 排除按钮/SeekBar/TimeBar/鼠标/TV/无障碍 |
| `SubtitleMenu` | `refresh()`、`show()`、`isShowing()` | `subtitleMode` 持久化：`auto`/`off`/`lang:<tag>` |
| `SourceScannerActivity` | 相机预览、图片识别、`deliver(text)` | 仅此页申请 `CAMERA`；解码在工作线程 |
| `PairingQr` | `create(url, size)` | ZXing 生成，容错级别 M，留白 4 |
| `SourceQrDecoder` | `sharePayload(json)`、`payload(raw)`、`bitmap(bmp)`、`preview(yuv, w, h)` | 支持 `libretv://sources?config=|url=|z=`；压缩负载 ≤1600 字符 |

---

## 7. 测试覆盖的纯逻辑

以下类不依赖 Android，便于 JVM 单测：

- `SegmentSkipPolicy` — 片头片尾规则。
- `NasAddress` — 地址解析与同源判断。
- `CastUrl` — 投屏地址白名单。
- `M3uPlaylist` — M3U 解析。
- `LiveRecoveryPolicy` — 恢复预算与线路切换。
- `LivePlaybackWatchdog` — 单调时钟采样状态机。
- `PhoneCastQueue` / `PhoneCastBatch` — 队列与批量顺序。
- `PhonePairingServer` — 一次性令牌与表单校验（配合测试钩子）。

下一篇：[04-data-storage.md](04-data-storage.md)。
