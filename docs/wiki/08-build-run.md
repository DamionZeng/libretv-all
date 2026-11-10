# 08 · 构建、运行与测试

本文说明环境要求、依赖安装、构建命令、Gradle 属性、运行方式、测试与发布流程。

---

## 1. 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 17（`sourceCompatibility/targetCompatibility = 17`，`jvmTarget = 17`） |
| Gradle | 8.9（`gradle/wrapper/gradle-wrapper.properties`） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 1.9.24 |
| Build Tools | 36.0.0 |
| compileSdk / targetSdk | 35 |
| minSdk | 23 |
| Android SDK | 需安装 Platform 35 与 Build Tools 36.0.0 |

依赖仓库（[settings.gradle](../../settings.gradle)）：`google()`、`mavenCentral()`、`jitpack.io`（仅 `com.github.yinnho`）。项目启用 `FAIL_ON_PROJECT_REPOS`，依赖仓库只能在 settings 中声明。

---

## 2. 构建命令

在仓库根目录执行：

```bash
# 调试包（不含本地真实源）
./gradlew -PincludeLocalSources=false :app:assembleDebug

# 单元测试 + Lint
./gradlew -PincludeLocalSources=false :app:testDebugUnitTest :app:lintDebug

# 发布包（需自行配置签名）
./gradlew -PincludeLocalSources=false :app:assembleRelease
```

Windows PowerShell 使用 `.\gradlew.bat`。

产物位置：

| 类型 | 路径 |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/` |
| 单元测试报告 | `app/build/reports/tests/testDebugUnitTest/` |
| Lint 报告 | `app/build/reports/lint-results-debug.html` |
| 默认源资产 | `app/build/generated/sourceAssets/` |

---

## 3. Gradle 属性

| 属性 | 默认值 | 作用 |
| --- | --- | --- |
| `-PincludeLocalSources` | `true` | 是否把 `local/default-sources.json` 与 `local/default-live-sources.json` 打进 APK；公开构建必须设为 `false` |
| `-PfamilyUpgrade` | `false` | 切换为旧包名 `family.libretv.tv` / 版本名 `0.21.0-independent`，输出目录改为 `app/build-family-upgrade` |
| `-PappVersionCode` | `58` | 覆盖 versionCode |

### 3.1 默认源资产任务

`prepareSourceAssets` 在 `preBuild` 前执行：

- `includeLocalSources=true` 且文件存在 → 复制 `local/*.json`；
- 否则写出 `{"sources":[]}` 与 `{"subscriptions":[]}`。

输出目录通过 `android.sourceSets.main.assets.srcDir(...)` 注入，因此不会污染源码目录。

### 3.2 依赖锁定

`app/build.gradle` 启用了 `dependencyLocking { lockAllConfigurations() }`。若升级依赖，需要同步更新 lockfile，否则构建会失败。

### 3.3 Lint

```groovy
lint { abortOnError true }
```

Lint 错误会中断构建；提交前应确保 `:app:lintDebug` 通过。

---

## 4. 运行

### 4.1 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4.2 首次启动

1. 电视：`MainActivity` → `TvHomeActivity`；进入“视频来源”，添加 CMS JSON 接口或扫码/局域网导入。
2. 手机：`MainActivity` 自动跳转 `PhoneActivity`；底部五个入口为首页、搜索、直播、历史、设置。
3. 没有来源时搜索为空，页面会给出添加入口。

### 4.3 设备形态验证

| 形态 | 验证点 |
| --- | --- |
| TV / 盒子 | 方向键可达所有控件；焦点粉色描边；返回逐级退出；播放页左右键快进快退 |
| 手机竖屏 | 底部导航；双击左右 ±10 秒；上下滑动亮度/音量 |
| 手机横屏 / 平板 / 车机 | 左侧导航；详情与设置宽屏双列；旋转后保留页面与草稿 |
| 低版本（API 23） | 包安装、播放、更新安装（v1 签名） |

### 4.4 常用调试入口

| 功能 | 入口 |
| --- | --- |
| 视频来源管理 | 设置 → 视频来源 |
| 直播订阅 | 设置 → 直播订阅 / TV 直播页管理 |
| 应用更新 | 设置 → 更新 |
| 局域网导入 | 视频来源 → 扫码 / 配对二维码 |
| 关于 | 设置 → 关于片刻 |

---

## 5. 测试

### 5.1 单元测试（JVM）

```bash
./gradlew -PincludeLocalSources=false :app:testDebugUnitTest
```

| 测试类 | 覆盖对象 |
| --- | --- |
| `SegmentSkipPolicyTest` | 片头片尾合法性、标题键、目标位置 |
| `PhonePairingServerTest` | 一次性令牌、表单校验 |
| `PhoneCastQueueTest` | 队列相邻集、索引、标签 |
| `PhoneCastBatchTest` | 批量顺序、失败处理、停止 |
| `NasAddressTest` | 地址解析、禁止 localhost/凭据、同源判断 |
| `M3uPlaylistTest` | EXTINF/EXTVLCOPT、去重、限额、拒绝媒体清单 |
| `LiveRecoveryPolicyTest` | 恢复预算、退避、线路切换、STOP |
| `LivePlaybackWatchdogTest` | 首帧/缓冲/冻结/稳定阈值 |
| `CastUrlTest` | 投屏地址白名单 |

设计上，纯逻辑类不依赖 Android，便于在 JVM 上快速回归。涉及 `SharedPreferences`、Media3、投屏引擎的类主要由仪器测试或人工验收覆盖。

### 5.2 仪器测试

```bash
./gradlew :app:connectedDebugAndroidTest
```

> 不要在用户真机上运行可能卸载或清数据的 instrumentation；使用模拟器或测试机。

### 5.3 手工回归清单

- 多源搜索：断网、单源超时、22 秒截止、增量结果；
- 详情与选集：`$$$` 多线路、m3u8 优先；
- 续播：退出重进、距结尾 15 秒内归零；
- 片头片尾：标记、自动跳过、倒计时取消、手动 seek 到片尾；
- 直播：首帧超时、缓冲、冻结、线路切换、冷却；
- 投屏：发现、单集、批量、切后台重连、音量/进度；
- 导入：扫码、图片识别、局域网表单、订阅；
- 更新：三通道、清单校验、断点恢复、签名不一致拒绝。

---

## 6. 发布

完整流程见 [docs/releasing.md](../releasing.md) 与 [07-update.md](07-update.md)。要点：

1. JDK 17 + 隔离 Gradle home；
2. `-PincludeLocalSources=false`；
3. 使用既有更新签名身份，minSdk 23 必须启用 v1 签名；
4. `apksigner verify --verbose`；
5. `scripts/prepare-release.py` 生成 APK、清单、`SHA256SUMS.txt`；
6. GitHub Release 与 Gitee `updates` 分支分别验证；
7. 匿名回下载并比对 SHA-256。

---

## 7. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 构建报依赖锁定不一致 | 依赖版本变化但 lockfile 未更新 | 更新 lockfile 或回退依赖版本 |
| Lint 中断构建 | `abortOnError true` | 修复告警；不要用 `-x lint` 绕过发布检查 |
| APK 内没有默认源 | `includeLocalSources=false` 或 `local/` 不存在 | 属预期；通过 UI 导入来源 |
| 更新提示“签名不一致” | 设备上安装的是其他签名的包 | 卸载旧包或使用同签名构建 |
| 低版本无法安装 | 未启用 v1 签名 | 重新签名并 `apksigner verify` |
| 投屏列表为空 | 手机与电视不在同一局域网 / 组播被过滤 | 检查网络；确认 `DLNACast.init` 已调用且组播锁获取成功 |
| 直播一直重连后停止 | 恢复预算耗尽 | 手动重试开始新一轮；检查线路可用性 |
| 搜索无结果 | 源被停用 / 接口失效 / 关键词不匹配 | 检查来源启用状态与接口地址 |

---

## 8. 代码约定与扩展建议

- 新增页面：TV 在 `MainActivity.Screen` 增加枚举并处理返回栈；手机在 `PhoneActivity.screen(name, heading)` 注册分支。
- 新增数据：优先新增 `*Store`/`*Repository`，统一放 SharedPreferences，并定义键规则与容量上限。
- 新增播放源类型：在 `NasClient`/`NasDataSource` 层处理访问控制，避免把协议细节散落到 Activity。
- 新增投屏能力：通过 `:upnpcast` 公共 API 扩展，不要在应用层直接拼 SOAP。
- 新增外部输入（URL/文件/二维码）：必须做大小限制、scheme/host 校验与失败回退。

---

返回 [Wiki 首页](README.md)。
