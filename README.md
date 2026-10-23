<p align="center">
  <img src="docs/assets/logo.svg" width="120" alt="RedNoteSeek">
</p>

<h1 align="center">RedNoteSeek</h1>

<p align="center">小红书第三方 Android 客户端：只做<strong>搜索与阅读</strong>，没有推荐流和广告。</p>

> [!WARNING]
> **仅供学习交流，不得用于商业用途。** 本项目是非官方客户端，与小红书（行吟信息科技）没有任何关联。内容版权归小红书及各位作者所有。请用自己的账号，自行承担账号风控等风险。

## 功能

- 🔍 **搜索笔记**：综合 / 最新 / 最多点赞 / 最多评论 / 最多收藏五种排序，双列瀑布流，带搜索历史
- ✨ **点点 AI**：搜索结果页显示 AI 总结和参考来源，可以继续追问；支持查看和删除会话历史
- 📖 **笔记详情**：图文和视频笔记，评论（含楼中楼），小红书表情
- 👤 **用户主页**：可以关注或取消关注，与账号同步
- ⭐ **收藏与专辑**：按专辑分文件夹浏览；可以新建、改名、删除专辑，在详情页收藏并加入专辑
- 📰 **动态**：只拉取关注作者里有未看笔记的人，其余用本地缓存
- ⬇️ **无水印下载**：图片和视频保存到相册
- 🌗 Material 3 动态取色、深色模式

不做：推荐 feed、发布、评论、私信、点赞、电商、直播。

## 工作原理

App 不直连任何小红书接口，也不自己计算请求签名。

它在隐藏的 WebView 里打开真实的小红书网页版（桌面 UA）。签名和 Cookie 都由网页自己处理。App 在页面加载前注入脚本，截获网页发出的接口响应，解析后用原生界面展示。翻页、打开笔记、收藏等操作都是在网页上模拟点击和滚动完成的。

所以本项目的行为与在浏览器里正常使用网页版一致，代价是每个页面都要真实加载网页，比直连接口慢。

## 安装与使用

1. 自行构建 APK（见下文），安装到 Android 8.0（API 26）及以上的手机。
2. 打开后点网页里的「登录」扫码登录。手机上装了小红书 App 的话，可以点底部「用小红书 App 登录」直接确认。
3. 登录状态保存在本机 WebView 中，App 不会把账号信息发送到任何第三方。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 37）。

```bash
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # 构建并安装到已连接的手机
```

单元测试、调试隐藏网页的方法和手工测试清单见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 技术栈

Kotlin · Jetpack Compose · Material 3 · Navigation Compose · MVVM · Coroutines + Flow · androidx.webkit · kotlinx-serialization · DataStore · Room · Coil 3 · Media3 ExoPlayer · OkHttp（仅用于下载）

## 项目结构

```
RedNoteSeek/
├── app/                 主入口：网页引擎、底部四标签外壳（搜索 / 动态 / 收藏 / 设置）
├── design-system/       主题与通用组件
├── core/
│   ├── web/             网页引擎：注入脚本、截获并解析响应、各页面数据源
│   ├── network/         响应数据模型、无水印下载
│   └── storage/         设置（DataStore），搜索历史、关注与动态缓存（Room）
└── feature/
    ├── auth/            登录引导
    ├── search/          搜索、排序、AI 总结
    ├── detail/          笔记详情、评论、视频播放、收藏、下载
    ├── profile/         用户主页、收藏与专辑、动态
    ├── dots/            点点 AI 会话
    └── settings/        设置
```

## 已知限制

- 网页版没有关注流。动态页先读取各作者的未看笔记数，只逐个打开有未看笔记的作者主页（每次间隔不少于 5 秒），并且只取首屏笔记。在小红书 App 里已经看过的新笔记不会出现。
- 换专辑或移出专辑要先取消收藏再重新收藏（网页只在刚收藏时能选专辑），所以收藏时间会变成当前时间。
- 遇到风控信号（HTTP 461、错误码 300011）会停止并提示，不自动重试。
- 模拟器、自动化环境、数据中心 IP 容易触发风控，请在真机上使用。
- 依赖网页版的页面结构，小红书改版后部分功能可能失效。

## 许可

代码以 [MIT License](LICENSE) 发布。随附字体 Shrikhand 使用 SIL Open Font License 1.1，见 [NOTICE](NOTICE)。

本许可只针对本仓库的代码，不授予任何小红书内容的使用权。使用者须遵守小红书的用户协议和所在地法律。
