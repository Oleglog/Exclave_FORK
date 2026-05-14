# Build Requirements

## 1. Установка зависимостей

### Go 1.25.9

> **Критично:** использовать именно **Go 1.25.x**. Go 1.26+ ломает 32-bit ARM на Android ≤ 10
> (см. [Known Issues](#known-issues)).

Скачать: <https://go.dev/dl/> → выбрать **go1.25.9** для своей ОС.

Или установить рядом с текущей версией Go:

```bash
go install golang.org/dl/go1.25.9@latest
go1.25.9 download
```

Проверка:

```bash
go1.25.9 version   # go version go1.25.9 ...
```

### gomobile + gobind

```bash
go1.25.9 install golang.org/x/mobile/cmd/gomobile@latest
go1.25.9 install golang.org/x/mobile/cmd/gobind@latest
gomobile init
```

### JDK 21

Подойдёт любой дистрибутив JDK 21:

- **Eclipse Adoptium (Temurin)** — <https://adoptium.net/temurin/releases/?version=21>
- **Oracle JDK** — <https://www.oracle.com/java/technologies/downloads/#java21>
- **Amazon Corretto** — <https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html>

### Android SDK + NDK

Установить через [Android Studio](https://developer.android.com/studio) → SDK Manager, либо через
[command-line tools](https://developer.android.com/studio#command-line-tools-only):

```bash
sdkmanager "platforms;android-37" "build-tools;37.0.0" "ndk;27.0.12077973"
```

Минимальные требования: **compileSdk 37**, **minSdk 21**, **targetSdk 36**, **NDK 27.x**.

### Gradle

Поставляется через wrapper (`gradlew` / `gradlew.bat`), отдельная установка **не требуется**.

---

## 2. Переменные окружения

Установить `ANDROID_HOME`, `ANDROID_NDK_HOME`, `JAVA_HOME` и добавить Go 1.25.9 в `PATH`.

**Linux / macOS:**

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export JAVA_HOME="/path/to/jdk-21"            # например /usr/lib/jvm/temurin-21-jdk
export GOROOT="$(go1.25.9 env GOROOT)"
export PATH="$GOROOT/bin:$PATH"
```

**Windows (PowerShell):**

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\27.0.12077973"
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" |
                  Select-Object -First 1).FullName          # авто-поиск JDK 21
$env:GOROOT = (go1.25.9 env GOROOT)
$env:PATH   = "$env:GOROOT\bin;$env:PATH"
```

> Если JDK установлен в нестандартном месте, укажите путь вручную.

---

## 3. Сборка AAR (Go-библиотека)

```bash
cd library/core

# Linux / macOS
./build.sh

# Windows
build.bat
```

Скрипт выполняет:

```
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
gomobile bind -v -androidapi 21 -trimpath \
  -ldflags="-s -buildid= -checklinkname=0" \
  -tags="with_clash" \
  -o libsagernetcore.aar \
  "github.com/dyhkwong/libsagernetcore" \
  "github.com/openlibrecommunity/olcrtc/mobile"
```

Результат автоматически копируется в `app/libs/libsagernetcore.aar`.

---

## 4. Источник olcRTC

Модуль `github.com/openlibrecommunity/olcrtc` собирается **не** напрямую из публичного репозитория. В `library/core/go.mod` стоит директива:

```
replace github.com/openlibrecommunity/olcrtc => ./olcrtc_local
```

Это значит, что несмотря на то что `gomobile bind` в `build.sh` / `build.bat` указывает upstream-импорт `github.com/openlibrecommunity/olcrtc/mobile`, реально в AAR попадает код из локальной директории `library/core/olcrtc_local/`.

В текущем форке выбран **вариант A** из исходного ТЗ: содержимое `olcrtc_local/` поддерживается как локальный snapshot эталонной ветки upstream `refactor/universal-carrier`. CI и `.gitmodules` не затронуты, обновление выполняется вручную.

> **Внимание.** Любая пересборка `library/core/build.sh` без обновления источника olcRTC даст AAR из устаревшего кода и **НЕ** починит транспорты `datachannel` / `telemost` / `seichannel`. Если изменения в Go-уровне (например, новые сигнатуры `Start`/`StartWithTransport`, новый формат фрейминга `seichannel`, исправления в `auth/salutejazz`) не доходят до Android-приложения после пересборки AAR — первое, что нужно проверить, это совпадает ли содержимое `library/core/olcrtc_local/` с эталонной ветки.

### Как обновить источник olcRTC (вариант A)

1. Сложить актуальный snapshot эталона в `temp-files/olcrtc-refactor-universal-carrier/` (либо склонировать `git@github.com:openlibrecommunity/olcrtc.git` в эту директорию из ветки `refactor/universal-carrier`, оставив `.git/` за пределами форка).
2. Удалить старую копию: `Remove-Item -Recurse -Force library/core/olcrtc_local` (Windows) или `rm -rf library/core/olcrtc_local` (Linux/macOS).
3. Скопировать snapshot: `Copy-Item -Recurse -Force temp-files/olcrtc-refactor-universal-carrier library/core/olcrtc_local` (Windows) или `cp -r temp-files/olcrtc-refactor-universal-carrier library/core/olcrtc_local` (Linux/macOS).
4. Обновить `go.sum`: `cd library/core && go mod tidy`.
5. Пересобрать AAR (см. пункт 3 выше).

Если в будущем форк перейдёт на git submodule (вариант B), команда обновления будет `git submodule update --remote library/core/olcrtc_local`. Если на удалённый модуль через `go.mod` (вариант C), команда будет `cd library/core && go get github.com/openlibrecommunity/olcrtc@<tag>` с одновременным удалением `replace` и директории `olcrtc_local/`.

---

## 5. Сборка APK

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

---

## Known Issues

- **Go ≥ 1.26 + 32-bit ARM + Android ≤ 10** — краш при старте:
  ```
  Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)
  Cause: seccomp prevented call to disallowed arm system call 422
  ```
  Go 1.26 использует `futex_time64` (syscall 422), который заблокирован seccomp-фильтром
  Android на API < 30. **Решение:** собирать AAR с **Go 1.25.9**.
