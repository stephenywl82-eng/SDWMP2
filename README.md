# 🎵 SDWMP3 — USB DAC 发烧级音乐播放器 v4.0

A modern Android music player built with Jetpack Compose and Material 3, featuring **USB DAC exclusive mode** for bit-perfect lossless audio output.

## ✨ 核心特性

- 🔊 **USB DAC 独占模式** — USB Audio Class 直通，绕过 Android 音频链路的 SRC 重采样，实现真正的 Bit-Perfect 输出
- 🎵 **libFLAC 硬解码** — 内置 libFLAC 原生解码器，支持任意位深/采样率的无损 FLAC 文件，无需依赖系统解码器
- 🔗 **Gapless 无缝衔接** — 跨曲目连续播放，音乐会不间断
- 💿 **CUE 整轨支持** — 自动加载同名 .cue 文件，实现整轨 FLAC/WAV 的无缝 Gapless 播放
- 📋 **自定义歌单** — 创建个人歌单，支持添加/删除歌曲，搜索筛选
- 📊 **CDJ 风格 VU 表** — 实时 5 频段可视化频谱，精准掌控每个频段
- 🎛️ **Hot Cue 记忆点** — 标记并快速回放关键时刻
- 📝 **自动歌词** — LRCLIB 歌词自动搜索 + 本地 LRC 文件兜底
- 🎨 **Material You 动态配色** — 封面主色自动生成全应用配色方案
- 🌊 **极光背景动效** — 流动极光视觉效果，贴合封面色彩
- 📱 **曲面屏边缘光** — 适配曲面屏的边缘呼吸灯效果

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| **UI** | Jetpack Compose + Material 3 |
| **架构** | MVI + Hilt DI + StateFlow |
| **音频** | Media3 ExoPlayer + libFLAC (C++ native) + USB Audio Class |
| **图片** | Coil + Palette API |
| **存储** | Room + DataStore Preferences |
| **构建** | Gradle KTS + R8 + ProGuard |

## 📦 下载

从 [Releases](https://github.com/stephenywl82-eng/SDWMP2/releases) 下载最新 APK。

> ⚠️ **要求**：Android 7.0 (API 24) 及以上。推荐搭配 USB DAC 使用以获得最佳音质体验。

## 🚀 从源码构建

```bash
# 克隆
git clone https://github.com/stephenywl82-eng/SDWMP2.git
cd SDWMP2

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK (需要 keystore)
./gradlew assembleRelease
```

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 35
- NDK r25b (用于编译 libFLAC 和 USB Audio 原生代码)

## 📁 项目结构

```
app/src/main/java/com/sdw/music/player/
├── core/
│   ├── audio/          # MusicService, USB DAC, Oboe, EQ, Visualizer
│   ├── lyrics/         # 歌词解析, LRCLIB 集成
│   └── model/          # Song, PlayerState 数据模型
├── di/                 # Hilt 依赖注入模块
├── ui/
│   ├── components/     # 可复用 Compose 组件
│   ├── navigation/      # 顶层导航
│   ├── screens/        # 所有页面 (播放器, EQ, 歌词等)
│   ├── theme/          # Material 3 主题 & 动态配色
│   └── viewmodel/      # PlayerViewModel, SongListViewModel
├── utils/              # CUE 解析器, 工具类
└── MainActivity.kt

app/src/main/cpp/       # USB Audio + libFLAC 原生引擎 (C++)
```

## 🎯 音频架构

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Music File │───▶│  libFLAC     │───▶│  USB Audio   │───▶ USB DAC
│   (FLAC/WAV) │    │  Decoder     │    │  Host API    │
└──────────────┘    │  (C++)       │    │  (C++)       │
                    └──────────────┘    └──────────────┘

备选路径 (非 USB DAC 模式):
┌──────────────┐    ┌──────────────┐
│  ExoPlayer   │───▶│  AAudio/     │───▶ 扬声器/耳机
│  (Media3)    │    │  Oboe        │
└──────────────┘    └──────────────┘
```

- **USB DAC 模式**：文件 → libFLAC 解码 → float PCM → USB Audio Host API → URB → DAC
- **标准模式**：ExoPlayer 软解 → AAudio/Oboe → 系统输出
- 自动检测 USB DAC，插入时无缝切换到独占模式

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)。

---

*Designed for audiophiles who demand bit-perfect playback.*
