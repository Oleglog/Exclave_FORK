# Build Requirements

## Prerequisites

| Tool | Version | Note |
|------|---------|------|
| **Go** | **1.25.9** | Go 1.26+ вызывает краш на 32-bit Android ≤ 10 (seccomp блокирует `futex_time64`) |
| **gomobile** | latest | `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init` |
| **gobind** | latest | `go install golang.org/x/mobile/cmd/gobind@latest` |
| **Android NDK** | 27.x | Через Android SDK Manager |
| **Android SDK** | compileSdk 37, minSdk 21, targetSdk 36 | |
| **JDK** | 21 | Eclipse Adoptium / Temurin рекомендуется |
| **Gradle** | 9.5+ | Поставляется через `gradlew` wrapper |

## Environment Variables

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
export JAVA_HOME=/path/to/jdk-21
```

Windows (PowerShell):
```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\27.0.12077973"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
```

## Step 1 — Build AAR (Go library)

```bash
cd library/core
# Linux / macOS
./build.sh

# Windows
build.bat
```

Скрипт выполняет:
```
gomobile bind -v -androidapi 21 -trimpath \
  -ldflags="-s -buildid= -checklinkname=0" \
  -tags="with_clash" \
  -o libsagernetcore.aar \
  "github.com/dyhkwong/libsagernetcore" \
  "github.com/openlibrecommunity/olcrtc/mobile"
```

Результат копируется в `app/libs/libsagernetcore.aar`.

## Step 2 — Build APK

```bash
# Из корня проекта
./gradlew :app:assembleOssDebug --no-daemon
```

APK появятся в `app/build/outputs/apk/oss/debug/`:
- `olcRTC-*-arm64-v8a-debug.apk`
- `olcRTC-*-armeabi-v7a-debug.apk`
- `olcRTC-*-x86-debug.apk`
- `olcRTC-*-x86_64-debug.apk`
- `olcRTC-*-universal-debug.apk`

Universal APK контролируется флагом `isUniversalApk` в `buildSrc/src/main/kotlin/Helpers.kt`.

## Known Issues

- **Go ≥ 1.26 + 32-bit ARM + Android ≤ 10** — краш при старте (`Fatal signal 31 SIGSYS`, `seccomp prevented call to disallowed arm system call 422`). Go 1.26 использует `futex_time64`, который заблокирован seccomp-фильтром Android на API < 30. Решение: собирать AAR с **Go 1.25.9**.
