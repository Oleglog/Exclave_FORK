# olcRTC for Android

[**Русский**](#русский) • [**English**](#english) • [**Сервер (Olcrtc_manager)**](https://github.com/Oleglog/Olcrtc_manager) • [**Releases**](https://github.com/Oleglog/Exclave_olcrtc/releases)

A fork of [Exclave](https://github.com/dyhkwong/Exclave) that integrates
[olcRTC](https://github.com/Oleglog/Olcrtc_manager) as a first-class proxy type.
olcRTC tunnels TCP traffic over WebRTC (datachannel / vp8channel / seichannel / videochannel) through whitelisted Russian conferencing
services (Yandex Telemost, SaluteJazz, Wildberries Stream) so it cannot be
blocked without breaking the upstream service.

> This is the **client** side of olcRTC. The server side is a separate
> repository — [Oleglog/Olcrtc_manager](https://github.com/Oleglog/Olcrtc_manager) —
> and you need to deploy it to a VPS before this app is useful.

---

## Русский

### Что это

Android-приложение со встроенным olcRTC-клиентом. Создаёшь профиль типа
**olcRTC**, вписываешь Provider / Room ID / Encryption Key, нажимаешь
*Connect* — приложение поднимает локальный SOCKS5 на телефоне, заворачивает
весь системный трафик через WebRTC-туннель к твоему VPS, и наружу выходит
с IP-адреса VPS.

```
[Apps на Android]
       │
       ▼
[Exclave VpnService]
       │
       ▼
[локальный SOCKS5 :2080]
       │
       ▼
[olcRTC-клиент]
       │
       ▼ WebRTC (datachannel / vp8channel / ...) (UDP)
[SFU видеоконф-сервиса в РФ — telemost.yandex.ru / wbstream / jazz.sber.ru]
       ▲
       │ WebRTC (datachannel / vp8channel / ...) (UDP)
       │
[olcRTC-сервер на твоём VPS вне РФ]
       │
       ▼
[Internet]
```

### Что добавлено в этом форке

По сравнению с upstream Exclave:

- **Новый тип профиля `olcRTC`** (`TYPE_OLCRTC = 30`) с собственной
  preferences-страницей: Provider / Transport / Room ID / Pre-shared key (hex) / DNS / VP8 options
- **Бридж к gomobile-биндингу olcRTC** — Java-обёртка над Go-библиотекой,
  поднимается перед V2Ray, и V2Ray видит туннель как обычный SOCKS5-апстрим
- **Объединённый AAR** (`library/core/main.go`): `dyhkwong/libsagernetcore` +
  `Oleglog/olcrtc/mobile` упакованы в один `libsagernetcore.aar` —
  один Go-runtime, один `libgojni.so` на ABI (без коллизии классов
  `go.Seq`)
- **Ребрендинг**: `applicationId = community.openlibre.olcrtc.android`,
  app name = **olcRTC**, отдельный `release.keystore` (signing certificate
  fingerprint отличается от upstream Exclave — поэтому **olcRTC** нельзя
  поставить поверх установленного Exclave и наоборот, это намеренно)
- **Локализация EN + RU**
- **Автоматический реконнект** с экспоненциальным backoff (до 10 попыток,
  1→2→4→8→16→30 с) при обрыве WebRTC-сессии
- **Keepalive-пинг** — фоновый пинг через SOCKS каждые N секунд (настраивается
  в UI, по умолчанию 15 с), удерживает сессию SFU при простое
- **Экспорт логов olcRTC** — кнопка в Logs для экспорта/копирования
  отфильтрованного лога с автоматическим удалением секретов (room_id, key_hex)

Все остальные протоколы Exclave (Shadowsocks, Trojan, Hysteria 2, VMess,
VLESS, WireGuard, и т.д.) сохранены — в одно приложение можно положить любую
комбинацию профилей.

### Установка APK

1. Скачай `olcRTC-<version>-arm64-v8a.apk` из
   [GitHub Releases](https://github.com/Oleglog/Exclave_olcrtc/releases).
   Для большинства современных Android-устройств это правильный вариант.
   Для старых 32-бит — `armeabi-v7a`. Для эмулятора — `x86_64`.

2. Поставь APK. Android попросит разрешения на установку из неизвестных
   источников — разреши **только** для устанавливающего приложения
   (Chrome / файловый менеджер).

3. **Не ставь** одновременно с обычным Exclave — у форка свой signing
   certificate, конфликт при апгрейде. Либо так, либо так.

### Настройка профиля

Перед настройкой убедись, что у тебя есть **развёрнутый olcRTC-сервер**
(см. [Oleglog/Olcrtc_manager](https://github.com/Oleglog/Olcrtc_manager#быстрый-старт-сервер-на-vps)).
После установки сервера ты получишь три значения:

- **Provider** (`wbstream` / `jazz` / `telemost`)
- **Room ID**
- **Encryption key** (64-символьный hex)

В приложении:

1. Открой **olcRTC** → нажми **+** в правом нижнем углу → **olcRTC**
2. Заполни:
   - **Profile name** — любое читаемое имя (например, `My VPS`)
   - **Provider** — то же что на сервере
   - **Transport** — по умолчанию `datachannel` (~6 МБ/с); также доступны `vp8channel`, `seichannel`, `videochannel`
   - **Room ID** — то же что на сервере
   - **Pre-shared key (hex)** — те же 64 символа
   - **DNS server** — оставь пустым (использует системный DNS) или укажи
     `1.1.1.1:53` / `8.8.8.8:53` если есть проблемы с резолвом провайдера
3. Сохрани (галочка вверху)
4. Нажми на профиль → правый нижний угол **Connect** (значок самолётика).
   Android запросит разрешение на VPN — разреши

Первое подключение занимает ~10–15 секунд (WebRTC-сессия + ICE-переговоры).
Повторные подключения быстрее.

### Импорт профиля

Вместо ручного заполнения полей можно импортировать готовый профиль через файл или QR.

**Формат JSON** (файл с расширением `.json`):

```json
{
  "version": 1,
  "type": "olcrtc",
  "name": "My VPS",
  "provider": "telemost",
  "transport": "datachannel",
  "room_id": "abc123",
  "key_hex": "64-символьный hex-ключ",
  "dns_server": "1.1.1.1:53"
}
```

Поля `name`, `transport` и `dns_server` — необязательны. Допустимые значения `provider`:
`telemost`, `jitsi`, `wbstream`. Допустимые значения `transport`:
`vp8channel` (рекомендуется), `datachannel`, `seichannel`, `videochannel`.

**Формат URI** (кодируется в QR):

```
olcrtc://<carrier>@room/<room_id>?key=<key_hex>&transport=<transport>#<name>
```

**Как импортировать:**

- **Через файл:** нажми **+** → **Import from file** → выбери `.json`
- **Через QR:** нажми **+** → **Scan QR code** → направь камеру
- **Из стороннего приложения:** открой `.json` → «Поделиться» → выбери **olcRTC**

**Как поделиться своим профилем:**

Открой профиль → меню (три точки) → **QR code** → покажи QR или скопируй URI.

### Подписки и QR-бандлы

Начиная с `olcrtc-2.0.28` клиент поддерживает QR-бандлы подписок из Admin UI сервера `server-v1.9.36+`. Такой QR содержит:

- ссылку на subscription URL, например `https://myolcrtc.mooo.com/sub/1r4Tmw`;
- snapshot текущих `olcrtc://` профилей из подписки;
- опциональный encrypted mirror URL и ключ расшифровки.

При сканировании QR-бандла приложение создаёт группу типа **Subscription**, сохраняет URL подписки и сразу добавляет все профили из snapshot. Это нужно для bootstrap-сценария, когда сервер подписок не доступен с мобильной сети до поднятия olcRTC-туннеля. Большие QR из Admin UI могут быть сжаты в формат `olcrtc+gz`, клиент `2.0.28+` распаковывает их автоматически.

Обновление подписки работает так:

1. если olcRTC/VPN уже подключён, запрос к subscription URL сначала идёт через активный proxy core, то есть через туннель;
2. если туннель не поднят и в группе есть encrypted mirror, клиент скачивает mirror, расшифровывает AES-256-GCM payload и импортирует профили;
3. если mirror недоступен, подключись к одному из уже импортированных профилей и обнови подписку через туннель.

Encrypted mirror через Yandex Disk поддержан экспериментально. Важно: Яндекс Диск может отдавать публичный файл через временный домен вида `*.storage.yandex.net`; если этот домен не доступен у мобильного оператора, обновление через mirror без туннеля не сработает. В таком случае QR-бандл всё равно полезен как первичный импорт, а дальнейшие обновления нужно выполнять через поднятый туннель.

### Проверка

Открой в браузере [https://2ip.ru](https://2ip.ru) или
[https://api.ipify.org](https://api.ipify.org). Должен показать IP **твоего
VPS**, а не твоего мобильного оператора. Если показывает оператора —
туннель не поднялся, посмотри логи (см. ниже).

### Отладка

В приложении: **Меню → Logs**. Сообщения от olcRTC-клиента отмечены тегом
`OlcRTC` (или `mobile.Mobile.*`). Что искать:

- `WB Stream/Jazz/Telemost room joined` — провайдерская регистрация прошла,
  подключение к SFU установлено
- `peer connected` — есть связь с сервером
- `i/o timeout` / `connection refused` — провайдер недоступен с устройства,
  либо нет сети
- `signature mismatch` / `decrypt error` — ключи не совпадают, проверь, что
  скопировал key в точности (без пробелов/переносов)

На сервере: `sudo journalctl -u olcrtc-server -f`. После твоего подключения
должна появиться строка `Peer 0 connected`. Если её нет — клиент не дошёл
до сервера, ищи проблему в провайдере или в сети устройства.

Самая частая причина "ничего не работает" — несовпадение Provider / Room ID
/ Key между сервером (`/etc/olcrtc/env`) и приложением. Сравни буквально
посимвольно.

### Сборка из исходников

#### Требования

| Инструмент    | Версия                                | Зачем                       |
|---------------|---------------------------------------|-----------------------------|
| JDK           | 21                                    | Gradle / Kotlin             |
| Android SDK   | Platform 36, Build-Tools 37.0.0      | сборка APK                  |
| Android NDK   | r29 (29.0.14206865)                   | нативные библиотеки V2Ray   |
| Go            | 1.25+                                 | сборка `libsagernetcore.aar`|
| `gomobile`    | latest                                | gomobile bind               |

Установка `gomobile`:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

Не забудь выставить переменные окружения:

```bash
export ANDROID_HOME=~/Android/Sdk           # или ANDROID_SDK_ROOT
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
```

#### Сборка

```bash
git clone https://github.com/Oleglog/Exclave_olcrtc
cd Exclave_FORK

# 1. Собрать объединённый Go AAR (libsagernetcore + olcrtc/mobile)
bin/lib/core/build.sh
# или эквивалентно:
./run lib core

# 2. Собрать debug APK
./gradlew :app:assembleOssDebug

# 3. Собрать подписанный release APK
./gradlew :app:assembleOssRelease
```

APK окажутся в `app/build/outputs/apk/oss/{debug,release}/` —
по одному на каждое ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).

#### Подпись релизной сборки

В корне репозитория лежит `release.keystore` (отдельный от upstream
Exclave). Пароли передавай через `local.properties`:

```
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

либо через переменные окружения с теми же именами. Без них сборка
подпишется debug-ключом.

#### Версионирование

Версии и applicationId зашиты в [`version.properties`](version.properties):

```
PACKAGE_NAME=community.openlibre.olcrtc.android
VERSION_NAME=0.17.37-olcrtc.5
VERSION_CODE=352
```

`VERSION_CODE` умножается ×5 при сборке — по 1 коду на каждое из 4 ABI плюс
universal slot.

### Структура репозитория (важное)

```
Exclave_FORK/
├── app/                              # Android-приложение (Kotlin/Java)
│   └── src/main/java/.../sagernet/
│       ├── fmt/olcrtc/OlcRTCBean.java          # модель профиля (Kryo)
│       ├── ui/profile/OlcRTCSettingsActivity   # preferences UI
│       └── bg/proto/OlcRTCInstance.kt          # бридж к gomobile-биндингу
├── library/
│   └── core/                         # Go-источник AAR
│       ├── main.go                   # импорт libsagernetcore + olcrtc/mobile
│       └── build.sh                  # gomobile bind
├── bin/                              # билд-скрипты
│   ├── build.sh                      # ./gradlew :app:assembleOssRelease
│   └── lib/core/build.sh             # билд AAR
├── plugin/                           # NaiveProxy / ShadowQuic плагины
└── version.properties                # PACKAGE_NAME / VERSION_NAME / VERSION_CODE
```

### Почему один AAR

Upstream `libsagernetcore` (V2Ray) и `olcrtc/mobile` оба собраны через
`gomobile bind`. У каждого получится свой AAR с собственными `go.*`
Java-классами и `libgojni.so` на ABI. Если положить два таких AAR в один
APK — Gradle упадёт:

```
Duplicate class go.Seq found in modules libsagernetcore.aar and olcrtc.aar
```

Решение в этом форке — импортировать `github.com/openlibrecommunity/olcrtc/mobile`
в тот же Go-модуль, что и `libsagernetcore`, и звать `gomobile bind` с
обоими пакетами на командной строке. Получается один AAR, экспортирующий
и `libsagernetcore.*`, и `mobile.*`, и единственный `libgojni.so` на ABI.

См. `library/core/main.go` и `library/core/build.sh`.

### Безопасность и приватность

- Бинарник olcRTC и сама приложение работают в одном процессе; нет отдельного
  daemon
- Encryption key — 32 байта (64 hex), используется для шифрования полезной
  нагрузки в DataChannel; детали — в [olcrtc_FORK README](https://github.com/Oleglog/Olcrtc_manager)
- Сорцы открыты, можно собрать APK самостоятельно и сравнить fingerprint с
  релизной сборкой через `apksigner verify --print-certs`
- В debug-логе могут оказаться секреты (Room ID, ключ) — стирай их перед
  публикацией баг-репортов

### Благодарности

- [dyhkwong/Exclave](https://github.com/dyhkwong/Exclave) — базовый прокси-клиент
- [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)
  — оригинальный проект olcRTC (серверная часть этого форка —
  [Oleglog/Olcrtc_manager](https://github.com/Oleglog/Olcrtc_manager))
- [SagerNet](https://github.com/SagerNet/SagerNet) — оригинальный Android
  прокси-фреймворк, от которого форкнут Exclave
- [@juushimatsu](https://github.com/juushimatsu)

### Лицензия

GPLv3 (унаследовано от Exclave). См. также `LICENSE` и `NOTICE.md`.

---

## English

### What this is

Android app with an embedded olcRTC client. You create a profile of type
**olcRTC**, fill in Provider / Room ID / Encryption Key, tap *Connect* —
the app starts a local SOCKS5 listener, points Android's VpnService at it,
and tunnels everything through a WebRTC DataChannel to your VPS, exiting
with the VPS's IP.

```
[Android apps]
       │
       ▼
[Exclave VpnService]
       │
       ▼
[local SOCKS5 :2080]
       │
       ▼
[olcRTC client]
       │
       ▼ WebRTC (datachannel / vp8channel / ...) (UDP)
[Russian conferencing SFU — telemost.yandex.ru / wbstream / jazz.sber.ru]
       ▲
       │ WebRTC (datachannel / vp8channel / ...) (UDP)
       │
[olcRTC server on your VPS abroad]
       │
       ▼
[Internet]
```

### What this fork adds vs. upstream Exclave

- **New profile type `olcRTC`** (`TYPE_OLCRTC = 30`) with full preferences UI
  (Provider, Transport, Room ID, Pre-shared key, DNS, VP8 options)
- **gomobile bridge** (`bg/proto/OlcRTCInstance.kt`) wrapping the Go olcRTC
  mobile binding; V2Ray treats the running tunnel as a plain SOCKS5 upstream
- **Combined AAR** (`library/core/main.go`) packing both
  `dyhkwong/libsagernetcore` and `Oleglog/olcrtc/mobile` into a single
  `libsagernetcore.aar` — one Go runtime, one `libgojni.so` per ABI (avoids
  the `go.Seq` class collision)
- **Rebrand**: `applicationId = community.openlibre.olcrtc.android`,
  app name = **olcRTC**, fresh release keystore (signing certificate
  fingerprint differs from upstream Exclave — **the app cannot be installed
  on top of an existing Exclave install** and vice versa, intentionally)
- **EN + RU localization**
- **Auto-reconnect** with exponential backoff (up to 10 attempts,
  1→2→4→8→16→30 s) when a WebRTC session drops
- **Keepalive ping** — background ping through SOCKS every N seconds
  (configurable in UI, default 15 s) to keep the SFU session alive during idle
- **olcRTC log export** — button in Logs to export/copy filtered logs with
  automatic secret redaction (room_id, key_hex)

All upstream Exclave protocols (Shadowsocks, Trojan, Hysteria 2, VMess,
VLESS, WireGuard, etc.) are preserved — you can mix any of them with olcRTC
profiles in the same install.

### Install the APK

1. Download `olcRTC-<version>-arm64-v8a.apk` from
   [GitHub Releases](https://github.com/Oleglog/Exclave_olcrtc/releases).
   Use `armeabi-v7a` for old 32-bit devices, `x86_64` for emulators.

2. Install. Android will prompt about unknown-source installation — allow
   it for the installer app only (Chrome / file manager).

3. **Don't install alongside upstream Exclave** — different signing
   certificates, so they conflict. One or the other.

### Profile setup

You need an **olcRTC server already running on a VPS** before this is useful.
See [Oleglog/Olcrtc_manager quick start](https://github.com/Oleglog/Olcrtc_manager#quick-start-server-on-vps).
The installer prints three values:

- **Provider** (`wbstream` / `jazz` / `telemost`)
- **Room ID**
- **Encryption key** (64-char hex)

In the app:

1. **olcRTC** → **+** → **olcRTC**
2. Fill:
   - **Profile name** — any readable name
   - **Provider** — match the server
   - **Transport** — default `datachannel` (~6 MB/s); also `vp8channel`, `seichannel`, `videochannel`
   - **Room ID** — match the server
   - **Pre-shared key (hex)** — exact 64 chars
   - **DNS server** — leave blank (use system DNS) or set `1.1.1.1:53` /
     `8.8.8.8:53` if you have provider DNS issues
3. Save (top-right checkmark)
4. Tap the profile → press **Connect** (paper-plane icon, bottom-right)
5. Approve Android's VPN permission prompt

First connection takes ~10–15 s (WebRTC negotiation + ICE). Reconnects are
faster.

### Import profile

Instead of typing the fields manually you can import a ready-made profile
from a file or QR code.

**JSON format** (file with `.json` extension):

```json
{
  "version": 1,
  "type": "olcrtc",
  "name": "My VPS",
  "provider": "telemost",
  "transport": "datachannel",
  "room_id": "abc123",
  "key_hex": "64-char hex key",
  "dns_server": "1.1.1.1:53"
}
```

`name`, `transport` and `dns_server` are optional. Valid `provider` values:
`telemost`, `jitsi`, `wbstream`. Valid `transport` values:
`vp8channel` (recommended), `datachannel`, `seichannel`, `videochannel`.

**URI format** (encoded in QR):

```
olcrtc://<carrier>@room/<room_id>?key=<key_hex>&transport=<transport>#<name>
```

**How to import:**

- **From file:** tap **+** → **Import from file** → pick the `.json`
- **From QR:** tap **+** → **Scan QR code** → point camera
- **From another app:** open the `.json` → Share → choose **olcRTC**

**How to share your profile:**

Open the profile → overflow menu → **QR code** → show the QR or copy the URI.

### Subscriptions and QR bundles

Starting with `olcrtc-2.0.28`, the client supports subscription QR bundles generated by Admin UI in `server-v1.9.36+`. A bundle contains:

- the subscription URL, for example `https://myolcrtc.mooo.com/sub/1r4Tmw`;
- a snapshot of current `olcrtc://` profiles from that subscription;
- an optional encrypted mirror URL and its decryption key.

When such a QR is scanned, the app creates a **Subscription** group, stores the subscription URL and immediately imports every profile from the snapshot. This is the bootstrap path for networks where the subscription server is not reachable before the olcRTC tunnel is up. Large QR payloads from Admin UI may be compressed as `olcrtc+gz`; client `2.0.28+` decompresses them automatically.

Subscription updates work as follows:

1. if olcRTC/VPN is already connected, the subscription URL is fetched through the active proxy core first, that is through the tunnel;
2. if the tunnel is down and the group has an encrypted mirror, the client downloads the mirror, decrypts the AES-256-GCM payload and imports the profiles;
3. if the mirror is unreachable, connect one of the already imported profiles and update the subscription through the tunnel.

Yandex Disk encrypted mirrors are experimental. Yandex Disk may serve a public file through a temporary `*.storage.yandex.net` domain; if that domain is not reachable from the mobile operator, mirror updates without a tunnel will fail. The QR bundle still remains useful for initial import, while regular updates can be done through the active tunnel.

### Verify

Open https://2ip.ru or https://api.ipify.org in a browser. The IP shown
should be your **VPS**, not your mobile carrier. If it's the carrier, the
tunnel didn't come up — check logs.

### Troubleshooting

App side: **Menu → Logs**. olcRTC-client messages are tagged `OlcRTC` (or
`mobile.Mobile.*`). What to look for:

- `WB Stream/Jazz/Telemost room joined` — provider registration succeeded
- `peer connected` — link to server is up
- `i/o timeout` / `connection refused` — provider unreachable from the
  device, or no network
- `signature mismatch` / `decrypt error` — keys don't match, recopy

Server side: `sudo journalctl -u olcrtc-server -f`. After your device
connects, you should see `Peer 0 connected`. If you don't, the client never
made it to the server — check provider connectivity / device network.

The most common "nothing works" cause is mismatched Provider / Room ID /
Key between server (`/etc/olcrtc/env`) and app. Compare character by
character.

### Build from source

#### Requirements

| Tool          | Version                              | Purpose                     |
|---------------|--------------------------------------|-----------------------------|
| JDK           | 21                                   | Gradle / Kotlin             |
| Android SDK   | Platform 36, Build-Tools 37.0.0     | APK assembly                |
| Android NDK   | r29 (29.0.14206865)                  | native V2Ray libs           |
| Go            | 1.25+                                | build `libsagernetcore.aar` |
| `gomobile`    | latest                               | gomobile bind               |

Install `gomobile`:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

Set env:

```bash
export ANDROID_HOME=~/Android/Sdk           # or ANDROID_SDK_ROOT
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
```

#### Build

```bash
git clone https://github.com/Oleglog/Exclave_olcrtc
cd Exclave_FORK

# 1. Build the merged Go AAR (libsagernetcore + olcrtc/mobile)
bin/lib/core/build.sh
# or:
./run lib core

# 2. Debug APK
./gradlew :app:assembleOssDebug

# 3. Signed release APK
./gradlew :app:assembleOssRelease
```

APKs land in `app/build/outputs/apk/oss/{debug,release}/`, one per ABI.

#### Release signing

`release.keystore` lives in the repo root (separate from upstream Exclave).
Provide passwords via `local.properties`:

```
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

or env vars of the same names. Without them, the build is signed with the
debug key.

#### Version bumping

Edit [`version.properties`](version.properties):

```
PACKAGE_NAME=community.openlibre.olcrtc.android
VERSION_NAME=0.17.37-olcrtc.5
VERSION_CODE=352
```

`VERSION_CODE` is multiplied ×5 during assembly — one slot per ABI plus a
universal slot.

### Repository layout (key files)

```
Exclave_FORK/
├── app/                              # Android app (Kotlin/Java)
│   └── src/main/java/.../sagernet/
│       ├── fmt/olcrtc/OlcRTCBean.java          # profile model (Kryo)
│       ├── ui/profile/OlcRTCSettingsActivity   # preferences UI
│       └── bg/proto/OlcRTCInstance.kt          # bridge to gomobile binding
├── library/
│   └── core/                         # Go source for the AAR
│       ├── main.go                   # imports libsagernetcore + olcrtc/mobile
│       └── build.sh                  # gomobile bind
├── bin/                              # build scripts
│   ├── build.sh                      # ./gradlew :app:assembleOssRelease
│   └── lib/core/build.sh             # build AAR
├── plugin/                           # NaiveProxy / ShadowQuic plugins
└── version.properties                # PACKAGE_NAME / VERSION_NAME / VERSION_CODE
```

### Why a single AAR

Both upstream `libsagernetcore` (V2Ray) and `olcrtc/mobile` are gomobile
bindings. Each produces an AAR with its own `go.*` Java classes and a
`libgojni.so` per ABI. Two such AARs in the same APK collide:

```
Duplicate class go.Seq found in modules libsagernetcore.aar and olcrtc.aar
```

The fix in this fork: import `github.com/openlibrecommunity/olcrtc/mobile`
into the same Go module that builds `libsagernetcore`, and call
`gomobile bind` with both packages. Result: one AAR exporting both
`libsagernetcore.*` and `mobile.*` Java packages, single `libgojni.so` per
ABI.

See `library/core/main.go` and `library/core/build.sh`.

### Security & privacy

- olcRTC runtime and the app run in the same process; no separate daemon
- Encryption key is 32 bytes (64 hex), used to encrypt the DataChannel
  payload (details in the [olcrtc_FORK README](https://github.com/Oleglog/Olcrtc_manager))
- All sources are open; you can build the APK yourself and compare the
  signing certificate fingerprint with releases via
  `apksigner verify --print-certs`
- Debug logs may contain secrets (Room ID, key) — strip before posting
  public bug reports

### License

GPLv3 (inherited from Exclave). See `LICENSE` and `NOTICE.md`.

### Acknowledgements

- [dyhkwong/Exclave](https://github.com/dyhkwong/Exclave) — base proxy
  client
- [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)
  — original olcRTC project (this fork's server lives at
  [Oleglog/Olcrtc_manager](https://github.com/Oleglog/Olcrtc_manager))
- [SagerNet](https://github.com/SagerNet/SagerNet) — original Android proxy
  framework Exclave is forked from
- [@juushimatsu](https://github.com/juushimatsu)
