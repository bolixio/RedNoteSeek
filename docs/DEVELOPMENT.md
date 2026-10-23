# 构建、安装、调试与测试

本文面向在本机构建 RedNoteSeek 并在真机上测试的人。

## 1. 环境准备

| 项目 | 要求 |
|---|---|
| Android SDK | compileSdk 37、targetSdk 36（Android Studio 的 SDK Manager 安装），minSdk 26 |
| JDK | 编译工具链为 **JDK 17**（各模块 `jvmToolchain(17)`，项目未配置自动下载，本机必须装有 JDK 17）；运行 Gradle 本身可用 Android Studio 自带的 JBR |
| 手机 | Android 8.0（API 26）及以上，已开启「开发者选项 → USB 调试」 |
| 账号 | 自己的小红书账号；手机上装有小红书 App 时登录最方便 |

`local.properties`（不入库）示例：

```properties
sdk.dir=/home/<你>/Android/Sdk
# 可选：让 Gradle 守护进程使用 Android Studio 自带的 JBR
org.gradle.java.home=/opt/android-studio/jbr
```

## 2. 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- debug 包的包名是 **`com.rednoteseek.debug`**（`applicationIdSuffix = ".debug"`），可与 release 包共存。
- 只有 debug 包会开启 WebView 远程调试（见第 5 节）。

## 3. 安装与启动

```bash
adb devices                                   # 确认手机已连接（状态为 device）
adb install -r app/build/outputs/apk/debug/app-debug.apk   # -r 覆盖安装，保留登录与数据
adb shell am start -n com.rednoteseek.debug/com.rednoteseek.app.MainActivity
```

也可以 `./gradlew installDebug` 一步构建并安装。

常用操作：

```bash
adb shell am force-stop com.rednoteseek.debug           # 强制停止
adb shell pm clear com.rednoteseek.debug                # 清空全部数据（会退出登录，慎用）
adb uninstall com.rednoteseek.debug                     # 卸载
```

## 4. 单元测试

```bash
# 全部单测（与 CI 一致）
./gradlew :core:network:testDebugUnitTest :core:storage:testDebugUnitTest :core:web:testDebugUnitTest \
          :feature:search:testDebugUnitTest :feature:detail:testDebugUnitTest :feature:profile:testDebugUnitTest \
          :feature:dots:testDebugUnitTest :design-system:testDebugUnitTest

# 单个模块 / 单个测试类
./gradlew :core:web:testDebugUnitTest
./gradlew :feature:profile:testDebugUnitTest --tests '*DynamicViewModelTest*'
```

| 模块 | 覆盖内容 |
|---|---|
| `core:network` | 视频地址选择、无水印图片 CDN 路径 |
| `core:storage` | 深色模式解析、关注表同步 |
| `core:web` | 截获响应解析、URL 校验、登录态、搜索 / 评论合并、网页脚本结构检查（`PageScriptsTest`） |
| `feature:search` | 搜索 ViewModel（排序、翻页、失败重试） |
| `feature:detail` | 笔记详情 ViewModel、正文处理 |
| `feature:profile` | 主页 / 收藏 / 动态 ViewModel（限频、风控停止、暂停） |
| `design-system` | 相对时间、媒体 / 封面比例 |

报告位置：`<模块>/build/reports/tests/testDebugUnitTest/index.html`。

> 修改了 `PageScripts` 里的网页脚本后务必跑 `:core:web:testDebugUnitTest`：脚本语法错误在手机上只会静默失败（所有笔记都打不开），单测能提前发现括号 / try-catch 不配对。

## 5. 调试

### 5.1 日志

```bash
adb logcat --pid=$(adb shell pidof com.rednoteseek.debug)          # 只看本 App
adb logcat --pid=$(adb shell pidof com.rednoteseek.debug) '*:E'    # 只看错误
adb logcat -b crash                                                # 崩溃堆栈
```

### 5.2 调试隐藏的网页（WebView 远程调试）

App 的数据都来自两个隐藏的 WebView（主引擎：搜索 / 笔记；第二引擎：主页 / 收藏 / 动态）。debug 包已开启远程调试：

1. 电脑上的 Chrome 打开 `chrome://inspect/#devices`，勾选 Discover USB devices。
2. 列表里会出现 `com.rednoteseek.debug` 下的几个页面（`/search_result/...`、`/user/profile/...`、`/explore/...`），点 **inspect**。
3. 在 Network 面板看 `/api/sns/web/` 请求的状态码（**461 表示触发风控**），在 Console 可执行只读 JS，例如 `location.href`。

命令行方式（不开 Chrome）：

```bash
PID=$(adb shell pidof com.rednoteseek.debug)
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl -s localhost:9222/json | grep -E '"url"|webSocketDebuggerUrl'
```

> 调试时**不要**在网页里调用路由、直接打开笔记链接或手动请求接口，这会触发 HTTP 461 风控。只读查看即可。

### 5.3 查看本地数据

```bash
adb shell run-as com.rednoteseek.debug ls files/datastore databases
# databases/rednoteseek.db：搜索历史（search_history）、关注（follows，账号关注名单的缓存）与动态缓存（dynamic_snapshots）
adb shell run-as com.rednoteseek.debug sqlite3 databases/rednoteseek.db 'select user_id,nickname from follows'
```

（部分手机没有 `sqlite3`，可用 Android Studio 的 App Inspection → Database Inspector。）

### 5.4 截图

```bash
adb exec-out screencap -p > shot.png
```

## 6. 手工测试清单

> **风控提醒**：请用真机 + 自己的账号，不要高频连点。出现「触发风控」提示时停止操作，过一段时间再试，并在小红书 App 里确认账号状态正常。

每项记录：通过 / 不通过 + 截图 + 第 5 节的日志。

### 6.1 登录与退出
| # | 操作 | 预期 |
|---|---|---|
| L1 | 全新安装后打开 | 上方显示小红书网页，底部显示「小红书网页版登录」引导 |
| L2 | 点网页里的「登录」，出现扫码框后点底部「用小红书 App 登录」，在小红书 App 中确认（也可用另一台手机扫码） | 自动进入搜索页 |
| L3 | 设置 → 退出登录 → 确认 | 回到登录引导 |
| L4 | 再次登录 | 正常进入，搜索可用 |
| L5 | 杀掉进程后重开 | 保持登录，不需要重新扫码 |

### 6.2 搜索
| # | 操作 | 预期 |
|---|---|---|
| S1 | 输入关键词搜索 | 双列瀑布流出现结果；关键词进入搜索历史 |
| S2 | 滑到底部 | 自动加载下一页，不重复 |
| S3 | 切换排序：最新 / 最多点赞 / 最多评论 / 最多收藏 / 综合 | 结果按所选排序刷新；切回原排序时结果保留 |
| S4 | 点历史记录、删除单条、清空 | 行为正确；设置里的「清空搜索历史」同样有效 |
| S5 | 断网后滑到底部 | 底部显示加载失败和「重试」；恢复网络后点重试可继续 |

### 6.3 笔记详情
| # | 操作 | 预期 |
|---|---|---|
| D1 | 点图文笔记 | 显示图片（可左右滑）、标题、正文、标签、点赞 / 收藏 / 评论数 |
| D2 | 点视频笔记 | 视频可播放，后台网页不发出声音 |
| D3 | 评论区下滑 | 评论分页加载 |
| D4 | 点「展开 N 条回复」 | 楼中楼展开 |
| D5 | 点下载按钮 | 图片 / 视频保存到相册，图片无水印 |
| D6 | 返回 | 回到搜索页原滚动位置，继续翻页正常 |
| D7 | 返回后再打开同一篇 / 另一篇 | 都能打开 |

### 6.4 用户主页
| # | 操作 | 预期 |
|---|---|---|
| P1 | 笔记详情点作者 | 打开该作者主页（头像、简介、关注 / 粉丝数、笔记） |
| P2 | 主页滑到底部 | 加载更多笔记 |
| P3 | 主页点笔记 → 返回 → 返回 → 返回 | 依次回到主页、详情、搜索页，搜索页仍可翻页 |

### 6.5 收藏
| # | 操作 | 预期 |
|---|---|---|
| C1 | 点「收藏」标签 | 显示专辑文件夹（2×2 封面、名称、笔记数），第一格是「全部收藏」 |
| C1b | 点某个专辑 | 进入专辑，显示其中的笔记；滑到底翻页直到「没有更多了」；点笔记能打开详情、返回后位置不变 |
| C1c | 点「全部收藏」 | 显示所有收藏的笔记（不分专辑） |
| C3 | 收藏页右上角「新建专辑」，填名称、关掉「公开」 | 列表出现该专辑（0 篇 · 私密）；小红书 App 里同步出现 |
| C4 | 进入专辑 → 右上角编辑 → 改名 | 标题变为新名称 |
| C5 | 搜索结果里打开未收藏的笔记 → 点顶栏星标 → 选专辑 | 星标变实心，提示「已加入「…」」；专辑里多出这篇 |
| C6 | 已收藏的笔记点星标 → 移出专辑 / 换专辑 | 先确认（收藏时间会变）；移出后笔记不在任何专辑但仍在全部收藏里 |
| C7 | 专辑编辑 → 删除专辑 → 确认 | 回到收藏页，专辑消失，笔记仍在全部收藏里 |
| C2 | 滑到底部、点笔记、返回 | 翻页正常，详情能打开，返回后列表还在 |

### 6.6 动态（账号关注）
| # | 操作 | 预期 |
|---|---|---|
| F0 | 启动 App 后进入「动态」 | 头像横排与小红书 App 里的关注列表一致（每次网页加载都会同步） |
| F1 | 主页右上角点「关注」 | 变为「已关注」，小红书 App 里的关注列表同步出现该作者 |
| F2 | 关注 2～3 位作者后进入「动态」 | 顶部显示作者头像横排，提示「下拉刷新」 |
| F3 | 下拉刷新 | 顶栏先显示「正在读取未看笔记数」，再显示「正在更新 1/N …」，每次页面加载间隔约 5 秒以上；完成后笔记按发布时间倒序，有未看笔记的作者头像带数字角标 |
| F3b | 再次下拉刷新 | 只访问有未看笔记的作者，提示「N 位作者没有未看的新笔记，沿用上次的内容」 |
| F3c | 杀掉 App 重开，进入「动态」 | 不刷新也显示上次的笔记 |
| F4 | 点动态里的笔记 | 稍等片刻后进入详情，可返回 |
| F5 | 刷新途中切到其他标签 | 刷新暂停；那个标签正常工作；回来下拉可重新刷新 |
| F6 | 点作者头像 → 点「已关注」取消 → 返回 | 该作者从横排和动态中移除，小红书 App 里也已取消关注 |
| F7 | **隔天**再下拉刷新 | 记录是否出现「作者更新失败」——用于确认保存的主页链接是否过期 |

### 6.7 设置与其他
| # | 操作 | 预期 |
|---|---|---|
| O1 | 深色模式：跟随系统 / 浅色 / 深色 | 立即生效，状态栏图标颜色随之变化 |
| O2 | 覆盖安装新版本 | 登录、搜索历史、关注都保留 |
| O3 | 旋转屏幕 / 切到后台再回来 | 不崩溃；正在进行的动态刷新会暂停（已知行为） |

## 7. 反馈问题时请附带

1. 测试项编号与操作步骤；
2. 截图或录屏（`adb shell screenrecord /sdcard/a.mp4`，Ctrl+C 结束后 `adb pull /sdcard/a.mp4`）；
3. 日志：`adb logcat -d --pid=$(adb shell pidof com.rednoteseek.debug) > app.log`；
4. 若怀疑风控：第 5.2 节 Network 面板中 `/api/sns/web/` 请求的状态码，以及界面上的提示文字。
