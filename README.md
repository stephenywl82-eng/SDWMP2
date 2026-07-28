# 🎵 SDWMP3 — Audiophile-Grade USB DAC Music Player v4.0

A modern Android music player built with Jetpack Compose and Material 3, featuring **USB DAC exclusive mode** for bit-perfect lossless audio output.

## ✨ Key Features

- 🔊 **USB DAC Exclusive Mode** — Direct USB Audio Class access, bypassing Android's audio pipeline SRC resampling for true bit-perfect output
- 🎵 **libFLAC Native Decoding** — Built-in libFLAC native decoder supporting lossless FLAC files at any bit depth / sample rate, no system codec dependency
- 🔗 **Gapless Playback** — Seamless track transitions, uninterrupted listening
- 💿 **CUE Sheet Support** — Auto-loads matching .cue files for gapless playback of single-file FLAC/WAV albums
- 📋 **Custom Playlists** — Create personal playlists with add/remove songs and search filtering
- 📊 **CDJ-Style VU Meters** — Real-time 5-band spectrum visualization
- 🎛️ **Hot Cues** — Mark and instantly recall key moments
- 📝 **Auto Lyrics** — LRCLIB automatic lyric search with local LRC file fallback
- 🎨 **Material You Dynamic Colors** — Full app color scheme generated from album art
- 🌊 **Aurora Background** — Flowing aurora visual effects matching album colors
- 📱 **Curved Edge Glow** — Breathing edge light effect for curved displays

## 🛠️ Tech Stack

| Layer | Technology |
|------|------|
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | MVI + Hilt DI + StateFlow |
| **Audio** | Media3 ExoPlayer + libFLAC (C++ native) + USB Audio Class |
| **Images** | Coil + Palette API |
| **Storage** | Room + DataStore Preferences |
| **Build** | Gradle KTS + R8 + ProGuard |

## 📦 Download

Get the latest APK from [Releases](https://github.com/stephenywl82-eng/SDWMP2/releases).

> ⚠️ **Requirements**: Android 7.0 (API 24) or above. A USB DAC is recommended for the best audio quality.

## 🚀 Build from Source

```bash
# Clone
git clone https://github.com/stephenywl82-eng/SDWMP2.git
cd SDWMP2

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (keystore required)
./gradlew assembleRelease
```

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 35
- NDK r25b (for building libFLAC and USB Audio native code)

## 📁 Project Structure

```
app/src/main/java/com/sdw/music/player/
├── core/
│   ├── audio/          # MusicService, USB DAC, Oboe, EQ, Visualizer
│   ├── lyrics/         # Lyric parsing, LRCLIB integration
│   └── model/          # Song, PlayerState data models
├── di/                 # Hilt dependency injection modules
├── ui/
│   ├── components/     # Reusable Compose components
│   ├── navigation/      # Top-level navigation
│   ├── screens/        # All screens (player, EQ, lyrics, etc.)
│   ├── theme/          # Material 3 theme & dynamic colors
│   └── viewmodel/      # PlayerViewModel, SongListViewModel
├── utils/              # CUE parser, utilities
└── MainActivity.kt

app/src/main/cpp/       # USB Audio + libFLAC native engine (C++)
```

## 🎯 Audio Architecture

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Music File │───▶│  libFLAC     │───▶│  USB Audio   │───▶ USB DAC
│   (FLAC/WAV) │    │  Decoder     │    │  Host API    │
└──────────────┘    │  (C++)       │    │  (C++)       │
                    └──────────────┘    └──────────────┘

Fallback path (non-USB DAC mode):
┌──────────────┐    ┌──────────────┐
│  ExoPlayer   │───▶│  AAudio/     │───▶ Speaker/Headphones
│  (Media3)    │    │  Oboe        │
└──────────────┘    └──────────────┘
```

- **USB DAC mode**: File → libFLAC decode → float PCM → USB Audio Host API → URB → DAC
- **Standard mode**: ExoPlayer software decode → AAudio/Oboe → system output
- Auto-detects USB DAC and seamlessly switches to exclusive mode on connection

## 📄 License

MIT License - see [LICENSE](LICENSE).

---

*Designed for audiophiles who demand bit-perfect playback.*
