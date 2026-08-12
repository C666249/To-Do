<p align="center">
  <img src="docs/assets/app-icon.png" width="112" alt="To-Do app icon" />
</p>

<h1 align="center">To-Do</h1>

<p align="center">
  <b>To-Do · Note · Daily</b><br>
  一个本地优先、轻量但功能完整的 Android 待办 / 笔记 / 每日重复小事应用。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.1.0">
  <img src="https://img.shields.io/badge/Version-1.19.1-2C8C7B" alt="Version 1.19.1">
  <img src="https://img.shields.io/badge/License-MIT-blue" alt="MIT License">
</p>

<p align="center">
  <a href="README_EN.md">English</a> ·
  <a href="#-截图与演示">截图</a> ·
  <a href="#-主要功能">功能</a> ·
  <a href="#-下载与安装">下载</a> ·
  <a href="#-构建">构建</a> ·
  <a href="#-隐私与数据">隐私</a>
</p>

<p align="center">
  <img src="docs/assets/hero.png" width="100%" alt="To-Do 应用功能预览" />
</p>

---

## ✨ 为什么做它

我想要一个不臃肿、不订阅、能把 **待办、长期笔记和每天重复的小事** 放在一起的个人效率工具。

To-Do 的核心数据保存在本机；提醒系统针对 Android 后台场景做了原生适配，同时保留了很多轻量、跟手的手势交互。

> 这是一个个人项目，也欢迎你 Fork、改造成自己喜欢的版本。

## 📱 截图与演示

<p align="center">
  <img src="docs/screenshots/onboarding-welcome.jpg" width="23%" alt="首次引导欢迎页" />
  <img src="docs/screenshots/todo-hidden-swipe.jpg" width="23%" alt="To-Do 隐藏操作引导" />
  <img src="docs/screenshots/daily-main.jpg" width="23%" alt="Daily 主面板" />
  <img src="docs/screenshots/note-mode.jpg" width="23%" alt="Note 模式" />
</p>

<p align="center">
  <img src="docs/screenshots/daily-summary-reminder.jpg" width="23%" alt="每日总结提醒" />
  <img src="docs/screenshots/daily-progress-calendar.jpg" width="23%" alt="Daily 历史进度日历" />
  <img src="docs/screenshots/daily-task-history.jpg" width="23%" alt="单项 Daily 历史" />
  <img src="docs/screenshots/note-images.jpg" width="23%" alt="Note 图文编辑" />
</p>

<p align="center"><sub>
首次引导 / To-Do 隐藏手势 / Daily 面板 / Note 长期记录 / 每日总结提醒 / Daily 历史完成度 / 单项 Daily 打卡轨迹 / Note 图文编辑
</sub></p>

### 🎬 手势短演示

<p align="center">
  <a href="docs/media/reminder-gestures.mp4">
    <img src="docs/media/reminder-gestures.gif" width="280" alt="提醒 Banner 四向手势实机演示" />
  </a>
</p>

<p align="center"><sub>Todo / Daily 提醒 Banner 支持右滑稍后提醒、左滑关闭、上滑完成、下滑打开 App。</sub></p>

如果你更喜欢直接看文件，也可以打开 [`docs/SCREENSHOTS.md`](docs/SCREENSHOTS.md)。

## 🚀 主要功能

### To-Do

- 未完成 / 进行中 / 已完成三态待办
- 分类、搜索、日历、回收站
- 单条待办定时提醒
- 左滑条目快速进入「提醒 / 删除」
- 可选 AI 助手，用自然语言创建、查询和整理待办

### Daily

- 独立于普通 To-Do 的每日重复事项
- 每天自动重新开始，不需要复制任务
- 历史完成日历与每日完成度
- 可查看某一天每条 Daily 的具体完成情况

### Note

- 文件夹、子笔记、富文本编辑
- 支持从系统相册一次多选图片
- 图片插入当前编辑位置
- 图片保存在 App 私有目录，不将大图 Base64 塞入 localStorage
- 图片预览与删除

### Reminder Banner

Todo / Daily 提醒支持一套四向手势：

- **右滑**：`+5 min / +10 min` 稍后提醒
- **左滑**：关闭当前提醒
- **上滑**：直接完成
- **下滑**：打开 App 并定位对应事项

四向手势达到阈值后会高亮并触发轻触觉反馈；未达到阈值则回弹。

### Onboarding

- 首次启动可视化主教程
- Spotlight、毛玻璃卡片与跟手演示
- 隐藏功能采用 Feature Coach 情景教学，不把说明一次性塞给用户
- 教程只保存独立状态，不修改真实待办 / 笔记 / Daily 数据

## 🔔 提醒机制

Android 原生层使用 `AlarmManager + Foreground Service + WindowManager Overlay` 组合实现提醒。

为了提高部分定制 Android 系统上的后台可靠性，App 可能需要：

- 通知权限
- 悬浮窗权限
- 精确闹钟权限
- 允许后台运行 / 关闭过度省电限制（视系统而定）

部分设备在启用精确定时后可能显示系统“下一闹钟”标记，这是 Android 系统行为。

## 📥 下载与安装

推荐从仓库的 [**Releases** 页面](https://github.com/C666249/To-Do/releases/latest) 下载最新 APK。

如果你从源码构建：

```text
Windows: build-apk.bat
```

成功后 APK 默认复制到：

```text
dist/To-Do-v1.19.1.apk
```

> Android 覆盖安装能否保留旧版本地数据，取决于 applicationId 与签名是否一致。不要为了升级先卸载旧版本。

## 🛠 构建

### 环境

- Android Studio
- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.7.3
- Kotlin 2.1.0
- compileSdk / targetSdk 35
- minSdk 26（Android 8.0）

### Android Studio

1. Clone 或下载本仓库。
2. 用 Android Studio **直接打开仓库最外层目录**。
3. 等待 Gradle Sync 完成。
4. 运行 `app`，或执行：

```bash
./gradlew assembleDebug
```

Windows 也可以直接运行：

```text
build-apk.bat
```

项目根目录的 `:app` 映射到 `android/app`。

## 🧱 技术结构

```text
To-Do/
├─ android/app/              # Kotlin Android 原生层
│  ├─ src/main/java/...      # Reminder / Overlay / Bridge
│  ├─ src/main/assets/       # WebView 页面
│  └─ src/main/res/          # 图标与 Android 资源
├─ ui/todo.html              # UI / 交互主源码镜像
├─ tests/                    # WebView / AI 相关轻量测试页
├─ docs/                     # 公共文档与截图
├─ gradle/                   # Gradle Wrapper
├─ build-apk.bat             # Windows 一键构建
└─ README.md
```

UI 主要使用 **HTML / CSS / Vanilla JavaScript**，Android 原生能力由 **Kotlin + WebView Bridge** 提供。

## 🤖 AI 功能说明

为了避免泄露开发者凭据，**开源仓库不内置任何真实 API Key**。

AI 助手属于可选功能，需要你自己配置兼容的 API Key。核心 To-Do / Note / Daily 功能不依赖 AI 服务。

请不要把自己的 Key 硬编码后提交到公开仓库。

## 🔐 隐私与数据

- To-Do / Note / Daily 核心数据以本地存储为主。
- Note 图片会复制进 App 私有文件目录。
- 项目不需要为了 Note 图片访问整个系统图库，使用系统图片选择器授权选择内容。
- AI 功能开启后，请求会发送至你配置的第三方 AI API 服务。

更多说明见 [`PRIVACY.md`](PRIVACY.md) 与 [`SECURITY.md`](SECURITY.md)。

## 🤝 Contributing

贡献说明见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。欢迎：

- 提 Issue 报告 Bug
- 提交 UI / 交互优化建议
- Fork 后实现自己喜欢的功能
- Pull Request

如果你喜欢这个项目，欢迎点一个 ⭐ Star，这会是对个人项目很大的鼓励。

## 📄 License

本项目使用 [MIT License](LICENSE)。
