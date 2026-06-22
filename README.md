# 🎵 SDWMP2 — Moto Music Player

A modern Android music player built with Jetpack Compose and Material 3, featuring native Oboe audio playback for bit-perfect sound quality.

## ✨ Features

- 🎧 **Native Audio Engine** — Oboe/AAudio playback with exclusive mode support for bit-perfect output
- 🎨 **Material You** — Dynamic theming via album art Palette extraction, every screen adapts to your music
- 🎚️ **Equalizer** — Full graphic EQ with AutoEQ presets for 1000+ headphones
- 📝 **LRC Lyrics** — Automatic lyric fetching via LRCLIB, with local file fallback
- 🖼️ **Widgets** — 4×1 and 3×2 home screen widgets with Material Design 3 styling
- 🌊 **Edge Glow** — Breathing light effects around the mini player, synced to playback
- 📱 **Foldable Ready** — Adaptive layout for foldable devices (flip & fold)
- 🔍 **Smart Scan** — MediaStore scanning with JSON disk cache for instant cold start
- 🏷️ **Hi-Res Tagging** — Visual tags for FLAC, WAV, Hi-Res audio formats
- 🌐 **A-Z Index** — Fast scroll alphabet index for artist and album lists
- 🎬 **Smooth Animations** — Shared element transitions between mini player and full player
- 📊 **Diagnostics** — Built-in audio diagnostic screen with real-time sharing mode monitoring

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | MVI + Hilt DI + StateFlow |
| **Audio** | Media3 ExoPlayer + Oboe (C++ native) |
| **Images** | Coil + Glide + Palette API |
| **Storage** | Room + DataStore Preferences |
| **Build** | Gradle KTS + R8 + ProGuard |

## 📦 Download

Get the latest APK from [Releases](https://github.com/stephenywl82-eng/SDWMP2/releases).

> ⚠️ **Note:** Minimum Android 7.0 (API 24). Optimized for Motorola devices.

## 🚀 Build from Source

```bash
# Clone
git clone https://github.com/stephenywl82-eng/SDWMP2.git
cd SDWMP2

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore)
./gradlew assembleRelease
```

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35
- NDK (for Oboe native code)

## 📁 Project Structure

```
app/src/main/java/com/sdw/music/player/
├── core/
│   ├── audio/          # MusicService, Oboe engine, EQ, Visualizer
│   ├── lyrics/         # Lyric parsing, LRCLIB integration
│   └── model/          # Song, PlayerState data models
├── di/                 # Hilt dependency injection modules
├── ui/
│   ├── animation/      # Shared element transitions
│   ├── components/     # Reusable Compose components
│   ├── navigation/     # Top-level navigation
│   ├── screens/        # All screens (Player, EQ, Lyrics, etc.)
│   ├── theme/          # Material 3 theme & dynamic color
│   └── viewmodel/      # PlayerViewModel, SongListViewModel
├── util/               # Pinyin utils, helpers
├── widget/             # Home screen widgets
└── MainActivity.kt

app/src/main/cpp/       # Oboe native audio engine (C++)
```

## 🎯 Audio Architecture

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  ExoPlayer   │───▶│  NDK Decoder │───▶│  Oboe Engine │───▶ Hardware
│  (Media3)    │    │  (C++)       │    │  (C++/AAudio)│
└──────────────┘    └──────────────┘    └──────────────┘
```

- ExoPlayer handles source reading & demuxing
- Custom NDK decoder extracts PCM frames
- Oboe engine renders via AAudio in exclusive mode
- Auto fallback to ExoPlayer if native path fails

## 📄 License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
