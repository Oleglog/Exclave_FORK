# ТЗ: Обновление клиента Exclave под новый сервер olcrtc

## Контекст

Upstream `openlibrecommunity/olcrtc` произвёл крупный рефакторинг:
- Свой мультиплексор → `smux` (несовместимый протокол)
- `provider` → `carrier` (переименование)
- `wb_stream` → `wbstream`
- Добавлены транспорты: `datachannel`, `videochannel`, `seichannel`, `vp8channel`
- Go mobile API: `StartWithProvider()` удалён → `Start()` / `StartWithTransport()`

**Без обновления клиент не сможет подключиться к новому серверу.**

## Матрица совместимости транспортов

| Transport | telemost | jazz | wbstream |
|-----------|:--------:|:----:|:--------:|
| datachannel | ✗ | ✓ | ✓ |
| vp8channel | ✓ | ✓ | ✓ |
| seichannel | ✗ | ✓ | ✓ |
| videochannel | ✓ | ✓ | ✓ |

Скорость по убыванию: datachannel (~6 МБ/с) > vp8channel > seichannel > videochannel (~200 КБ/с)

## Изменения в Go mobile API

### Было (старый `mobile/mobile.go`):
```go
func StartWithProvider(providerName, roomID, keyHex string, socksPort int, socksUser, socksPass string) error
```

### Стало (новый `mobile/mobile.go`):
```go
func Start(carrierName, roomID, keyHex string, socksPort int64, socksUser, socksPass string) error
func StartWithTransport(carrierName, transportName, roomID, keyHex string, socksPort int64, socksUser, socksPass string) error
func SetTransport(transport string)    // "datachannel", "vp8channel", "seichannel", "videochannel"
func SetLink(link string)              // "direct"
func SetDNS(dnsServer string)
func SetVP8Options(fps, batchSize int)
func SetProviders()                    // регистрирует все carrier-ы
```

`Start()` использует текущий транспорт (по умолчанию `datachannel`).
`StartWithTransport()` позволяет задать транспорт явно.

## Файлы для изменения

### 1. `library/core/go.mod` — обновить зависимость

```
replace (
    github.com/openlibrecommunity/olcrtc => github.com/Oleglog/olcrtc_FORK v0.0.0-НОВЫЙ_ХЕШ
)
```

После пуша мержа в `Oleglog/olcrtc_FORK` нужно:
```bash
cd library/core
GOPROXY=direct go get github.com/Oleglog/olcrtc_FORK@master
go mod tidy
```

### 2. `OLCRTCBean.java` — добавить поля transport + vp8

**Добавить константы и поля:**
```java
// Carrier constants
public static final String PROVIDER_TELEMOST = "telemost";
public static final String PROVIDER_JAZZ = "jazz";
public static final String PROVIDER_WB_STREAM = "wbstream";  // было "wb_stream"

// Transport constants
public static final String TRANSPORT_DATACHANNEL = "datachannel";
public static final String TRANSPORT_VP8CHANNEL = "vp8channel";
public static final String TRANSPORT_SEICHANNEL = "seichannel";
public static final String TRANSPORT_VIDEOCHANNEL = "videochannel";

public String provider;
public String transport;     // НОВОЕ
public String roomId;
public String keyHex;
public String dnsServer;
public int vp8Fps;           // НОВОЕ
public int vp8BatchSize;     // НОВОЕ
```

**Обновить `initializeDefaultValues()`:**
```java
if (provider == null || provider.isEmpty()) provider = PROVIDER_TELEMOST;
if (transport == null || transport.isEmpty()) transport = TRANSPORT_DATACHANNEL;  // НОВОЕ
if (roomId == null) roomId = "";
if (keyHex == null) keyHex = "";
if (dnsServer == null || dnsServer.isEmpty()) dnsServer = "1.1.1.1:53";
if (vp8Fps <= 0) vp8Fps = 60;            // НОВОЕ
if (vp8BatchSize <= 0) vp8BatchSize = 8;  // НОВОЕ
```

**Обновить Kryo `serialize()` / `deserialize()`:**

Важно: увеличить версию сериализации с `0` на `1` для обратной совместимости:
```java
@Override
public void serialize(ByteBufferOutput output) {
    output.writeInt(1);  // было 0
    super.serialize(output);
    output.writeString(provider);
    output.writeString(roomId);
    output.writeString(keyHex);
    output.writeString(dnsServer);
    // v1 fields:
    output.writeString(transport);
    output.writeInt(vp8Fps);
    output.writeInt(vp8BatchSize);
}

@Override
public void deserialize(ByteBufferInput input) {
    int version = input.readInt();
    super.deserialize(input);
    provider = input.readString();
    roomId = input.readString();
    keyHex = input.readString();
    dnsServer = input.readString();
    if (version >= 1) {
        transport = input.readString();
        vp8Fps = input.readInt();
        vp8BatchSize = input.readInt();
    }
}
```

### 3. `OLCRTCExternalInstance.kt` — вызов нового API с транспортом

**Было:**
```kotlin
Mobile.startWithProvider(
    bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST },
    bean.roomId,
    bean.keyHex,
    port.toLong(),
    username,
    password,
)
```

**Стало:**
```kotlin
// Нормализация wb_stream → wbstream для старых профилей
val carrier = when (bean.provider) {
    "wb_stream" -> "wbstream"
    else -> bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST }
}
val transport = bean.transport.ifBlank { OLCRTCBean.TRANSPORT_DATACHANNEL }

// Настроить transport и link перед запуском
Mobile.setTransport(transport)
Mobile.setLink("direct")

// VP8 параметры (если выбран vp8channel)
if (transport == OLCRTCBean.TRANSPORT_VP8CHANNEL) {
    Mobile.setVP8Options(
        bean.vp8Fps.toLong(),
        bean.vp8BatchSize.toLong(),
    )
}

Mobile.startWithTransport(
    carrier,
    transport,
    bean.roomId,
    bean.keyHex,
    port.toLong(),
    username,
    password,
)
```

> **Примечание:** `Mobile.start()` тоже работает (использует текущий
> `SetTransport()`), но `startWithTransport()` надёжнее — не зависит от
> глобального состояния.

### 4. `OLCRTCFmt.kt` — обновить валидацию, URI и JSON

**Валидация:**
```kotlin
private val VALID_PROVIDERS = setOf(
    OLCRTCBean.PROVIDER_TELEMOST,
    OLCRTCBean.PROVIDER_JAZZ,
    OLCRTCBean.PROVIDER_WB_STREAM,
    "wb_stream",  // обратная совместимость со старыми URI
)

private val VALID_TRANSPORTS = setOf(
    OLCRTCBean.TRANSPORT_DATACHANNEL,
    OLCRTCBean.TRANSPORT_VP8CHANNEL,
    OLCRTCBean.TRANSPORT_SEICHANNEL,
    OLCRTCBean.TRANSPORT_VIDEOCHANNEL,
)
```

**Нормализация carrier:**
```kotlin
private fun normalizeCarrier(p: String): String = when (p) {
    "wb_stream" -> OLCRTCBean.PROVIDER_WB_STREAM
    else -> p
}
```

**Парсинг URI — добавить transport:**

Новый формат URI:
```
olcrtc://<carrier>@room/<room_id>?key=<hex>&transport=<transport>#<name>
```

```kotlin
fun parseOLCRTC(url: String): OLCRTCBean {
    val link = Libsagernetcore.parseURL(url)
    return OLCRTCBean().apply {
        provider = normalizeCarrier(link.username)
        roomId = link.path.trimStart('/')
        keyHex = link.queryParameter("key") ?: ""
        dnsServer = link.queryParameter("dns") ?: "1.1.1.1:53"
        transport = link.queryParameter("transport") ?: OLCRTCBean.TRANSPORT_DATACHANNEL
        name = link.fragment ?: ""
        validate()
    }
}
```

**Генерация URI — добавить transport:**
```kotlin
fun OLCRTCBean.toUri(): String {
    val builder = Libsagernetcore.newURL("olcrtc").apply {
        setHostPort("room", 1)
        username = provider
        path = "/$roomId"
        addQueryParameter("key", keyHex)
        if (transport.isNotEmpty() && transport != OLCRTCBean.TRANSPORT_DATACHANNEL) {
            addQueryParameter("transport", transport)
        }
        if (dnsServer.isNotEmpty() && dnsServer != "1.1.1.1:53") {
            addQueryParameter("dns", dnsServer)
        }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    return builder.string
}
```

**Парсинг JSON — добавить transport:**
```kotlin
fun parseOLCRTCJson(text: String): OLCRTCBean {
    val json = JSONObject(text)
    check(json.optString("type") == "olcrtc") { "Not an olcRTC config" }
    return OLCRTCBean().apply {
        name = json.optString("name", "")
        provider = normalizeCarrier(json.optString("provider", ""))
        transport = json.optString("transport", OLCRTCBean.TRANSPORT_DATACHANNEL)
        roomId = json.optString("room_id", "")
        keyHex = json.optString("key_hex", "")
        dnsServer = json.optString("dns_server", "1.1.1.1:53")
        validate()
    }
}
```

**Обновить validate():**
```kotlin
private fun OLCRTCBean.validate() {
    check(provider in VALID_PROVIDERS) { "Unknown carrier: $provider" }
    check(transport in VALID_TRANSPORTS) { "Unknown transport: $transport" }
    check(roomId.isNotEmpty()) { "room_id is required" }
    check(HEX_REGEX.matches(keyHex)) { "key_hex must be 64 hex characters" }
}
```

### 5. `OLCRTCSettingsActivity.kt` — добавить UI для транспорта

**Обновить `init()` и `serialize()`:**
```kotlin
override fun OLCRTCBean.init() {
    DataStore.profileName = name
    DataStore.serverOlcrtcProvider = provider
    DataStore.serverOlcrtcTransport = transport       // НОВОЕ
    DataStore.serverOlcrtcRoomId = roomId
    DataStore.serverOlcrtcKeyHex = keyHex
    DataStore.serverOlcrtcDnsServer = dnsServer
    DataStore.serverOlcrtcVp8Fps = vp8Fps             // НОВОЕ
    DataStore.serverOlcrtcVp8BatchSize = vp8BatchSize  // НОВОЕ
}

override fun OLCRTCBean.serialize() {
    name = DataStore.profileName
    provider = DataStore.serverOlcrtcProvider.ifEmpty { OLCRTCBean.PROVIDER_TELEMOST }
    transport = DataStore.serverOlcrtcTransport.ifEmpty { OLCRTCBean.TRANSPORT_DATACHANNEL }
    roomId = DataStore.serverOlcrtcRoomId
    keyHex = DataStore.serverOlcrtcKeyHex
    dnsServer = DataStore.serverOlcrtcDnsServer.ifEmpty { "1.1.1.1:53" }
    vp8Fps = DataStore.serverOlcrtcVp8Fps
    vp8BatchSize = DataStore.serverOlcrtcVp8BatchSize
    serverAddress = "olcrtc"
    serverPort = 1
}
```

### 6. `DataStore` — добавить новые ключи

```kotlin
var serverOlcrtcTransport by StringPref("serverOlcrtcTransport", "datachannel")
var serverOlcrtcVp8Fps by IntPref("serverOlcrtcVp8Fps", 60)
var serverOlcrtcVp8BatchSize by IntPref("serverOlcrtcVp8BatchSize", 8)
```

### 7. `app/src/main/res/values/arrays.xml` — значения в списках

```diff
  <string-array name="olcrtc_provider_value">
      <item>telemost</item>
      <item>jazz</item>
-     <item>wb_stream</item>
+     <item>wbstream</item>
  </string-array>

+ <string-array name="olcrtc_transport_entry">
+     <item>datachannel (~6 МБ/с)</item>
+     <item>vp8channel (универсальный)</item>
+     <item>seichannel</item>
+     <item>videochannel (~200 КБ/с)</item>
+ </string-array>
+
+ <string-array name="olcrtc_transport_value">
+     <item>datachannel</item>
+     <item>vp8channel</item>
+     <item>seichannel</item>
+     <item>videochannel</item>
+ </string-array>
```

### 8. `olcrtc_preferences.xml` — добавить ListPreference для транспорта

После существующего `olcrtc_provider`:
```xml
<ListPreference
    android:key="serverOlcrtcTransport"
    android:title="@string/olcrtc_transport"
    android:entries="@array/olcrtc_transport_entry"
    android:entryValues="@array/olcrtc_transport_value"
    android:defaultValue="datachannel" />
```

Опционально — VP8 настройки (показывать только при `transport == vp8channel`):
```xml
<EditTextPreference
    android:key="serverOlcrtcVp8Fps"
    android:title="VP8 FPS"
    android:inputType="number"
    android:defaultValue="60" />
<EditTextPreference
    android:key="serverOlcrtcVp8BatchSize"
    android:title="VP8 Batch Size"
    android:inputType="number"
    android:defaultValue="8" />
```

### 9. `strings.xml` — новые строки

```xml
<string name="olcrtc_transport">Transport</string>
```

### 10. Миграция сохранённых профилей

Пользователи со старыми профилями `wb_stream` не смогут подключиться.

- **В `OLCRTCExternalInstance.launch()`** уже нормализуем `"wb_stream"` → `"wbstream"` (см. п.3)
- **В `OLCRTCBean.deserialize()`** при `version == 0` транспорт будет `null` → `initializeDefaultValues()` поставит `datachannel`
- Старые URI `olcrtc://wb_stream@room/...` обрабатываются через `normalizeCarrier()` в `parseOLCRTC`

### 11. `README-olcRTC.md` — обновить документацию

- Заменить все `wb_stream` → `wbstream` в примерах
- Добавить Transport в описание полей профиля и JSON/URI форматов
- Обновить диаграмму: "WebRTC DataChannel" → "WebRTC (datachannel / vp8channel / ...)"

## Сборка нового AAR

После обновления `go.mod`:
```bash
cd library/core
gomobile bind -target=android -androidapi 21 -o ../../app/libs/libsagernetcore.aar .
```

Либо через `bin/lib/core/build.sh` / `./run lib core`.

## Итого

| Файл | Изменение | Объём |
|------|-----------|-------|
| `library/core/go.mod` | Обновить хеш зависимости | 1 строка |
| `OLCRTCBean.java` | `"wb_stream"` → `"wbstream"`, добавить transport/vp8 поля, Kryo v1 | ~30 строк |
| `OLCRTCExternalInstance.kt` | `startWithProvider` → `startWithTransport` + transport/vp8 логика | ~20 строк |
| `OLCRTCFmt.kt` | Нормализация carrier, transport в URI/JSON, validate | ~30 строк |
| `OLCRTCSettingsActivity.kt` | Добавить transport/vp8 в init/serialize | ~6 строк |
| `DataStore` | Добавить 3 новых ключа | 3 строки |
| `arrays.xml` | `wb_stream` → `wbstream`, добавить transport arrays | ~15 строк |
| `olcrtc_preferences.xml` | ListPreference для транспорта + VP8 | ~15 строк |
| `strings.xml` | `olcrtc_transport` строка | 1 строка |
| `README-olcRTC.md` | Обновить примеры и диаграмму | ~20 строк |

**Обратная совместимость URI:** старые ссылки `olcrtc://wb_stream@room/...`
будут работать через нормализацию в `parseOLCRTC`. URI без `transport=` получат
`datachannel` по умолчанию.

**Обратная совместимость Kryo:** старые профили (version 0) десериализуются
без ошибок — transport/vp8 поля получат дефолты через `initializeDefaultValues()`.

**Критично:** клиент и сервер обновляются **одновременно** — протокол `smux`
не совместим со старым мультиплексором.
