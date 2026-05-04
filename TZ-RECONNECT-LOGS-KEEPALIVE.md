# ТЗ: Reconnect Backoff + Export логов + Keepalive-пинг

> Версия: 1.0  
> Дата: 2026-05-04  
> Базовая ветка: `dev` (после `v0.17.37-olcrtc.5`)

---

## 0. Контекст

### Текущее поведение

1. **Нет реконнекта.** Если WebRTC-сессия падает (ICE disconnect, сервер
   рестартнул, сеть переключилась Wi-Fi → LTE), `Mobile.stop()` не вызывается
   автоматически; соединение «зависает», и пользователь должен вручную
   отключиться и подключиться заново. Если же `launch()` упал при первичном
   подключении, `BaseService` вызывает `stopRunner(false, msg)` и VPN гаснет.

2. **Экспорт логов неудобен.** `LogcatFragment` умеет отправить весь logcat через
   share-intent (`action_send_logcat`), но:
   - в лог попадают секреты (room_id, key_hex) из `[olcrtc]`-строк;
   - нет кнопки «скопировать в буфер»;
   - экспортируются *все* теги, а не только релевантные olcRTC.

3. **Нет keepalive.** Если VPN включён, но трафика нет (пользователь не серфит),
   WebRTC-сессия может отвалиться по таймауту SFU (обычно 30–60 с без медиа
   или data). Нужен фоновый пинг, чтобы сессия оставалась живой.

### Затрагиваемые файлы (ожидание)

| Файл | Что меняется |
|------|-------------|
| `OLCRTCExternalInstance.kt` | reconnect-цикл, keepalive-корутина |
| `OLCRTCBean.java` | новые поля: `keepaliveIntervalSec`, Kryo v2 |
| `OLCRTCFmt.kt` | URI/JSON: `keepalive=` |
| `OLCRTCSettingsActivity.kt` | init/serialize keepalive |
| `Constants.kt` | `SERVER_OLCRTC_KEEPALIVE_INTERVAL` |
| `DataStore.kt` | `serverOlcrtcKeepaliveInterval` |
| `olcrtc_preferences.xml` | EditTextPreference keepalive |
| `strings.xml` (EN + RU) | строка `olcrtc_keepalive_interval` |
| `arrays.xml` | — (нет новых массивов) |
| `LogcatFragment.kt` | кнопка «Export olcRTC log» + санитизация |
| `logcat_menu.xml` | новый пункт меню |
| `version.properties` | bump |

---

## 1. Reconnect с экспоненциальным backoff

### 1.1. Требования

| # | Требование |
|---|-----------|
| R1 | При падении WebRTC-сессии (исключение из Go-слоя или обнаруженный disconnect) клиент автоматически пытается переподключиться. |
| R2 | Задержка между попытками — экспоненциальный backoff: 1 с → 2 с → 4 с → 8 с → 16 с → 30 с (cap). |
| R3 | Максимум 10 попыток подряд, после чего — `stopRunner(false, "olcRTC: reconnect failed after 10 attempts")`. |
| R4 | Успешное подключение сбрасывает счётчик попыток. |
| R5 | Во время переподключения V2Ray-инстанс **не** перезапускается — переподнимается только Go-клиент olcRTC (`Mobile.stop()` → `Mobile.startWithTransport()`). |
| R6 | Пользователь видит в нотификации статус «Reconnecting… (attempt 3/10)» во время backoff. |
| R7 | Ручной `close()` немедленно прерывает цикл реконнекта. |

### 1.2. Архитектура

```
OLCRTCExternalInstance
├── launch()                    # первый запуск (как сейчас)
├── reconnectLoop()             # корутинный цикл
│   ├── Mobile.stop()
│   ├── delay(backoff)
│   ├── Mobile.startWithTransport(…)
│   ├── Mobile.waitReady(15_000)
│   └── если ОК → сбросить счётчик, иначе → следующая итерация
├── close()                     # cancel reconnectJob
└── onSessionLost()             # вызывается из Go-колбэка или health-check
```

### 1.3. Изменения в `OLCRTCExternalInstance.kt`

```kotlin
class OLCRTCExternalInstance(
    private val bean: OLCRTCBean,
    private val port: Int,
    private val username: String,
    private val password: String,
) : AbstractInstance {

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    @Volatile private var started = false
    @Volatile private var closing = false

    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === Вспомогательный метод: один цикл запуска Go-клиента ===
    private fun startGoClient() {
        val carrier = when (bean.provider) {
            "wb_stream" -> "wbstream"
            else -> bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST }
        }
        val transport = bean.transport.ifBlank { OLCRTCBean.TRANSPORT_DATACHANNEL }

        Mobile.setTransport(transport)
        Mobile.setLink("direct")

        if (transport == OLCRTCBean.TRANSPORT_VP8CHANNEL) {
            Mobile.setVP8Options(
                bean.vp8Fps.toLong(),
                bean.vp8BatchSize.toLong(),
            )
        }

        Mobile.startWithTransport(
            carrier, transport,
            bean.roomId, bean.keyHex,
            port.toLong(), username, password,
        )
        Mobile.waitReady(15_000L)
    }

    // === launch() — первый запуск ===
    override fun launch() {
        closing = false
        setupMobileCallbacks()
        startGoClient()
        started = true
        // запустить мониторинг (keepalive + health-check → onSessionLost)
    }

    // === Reconnect-цикл ===
    private fun onSessionLost(reason: String) {
        if (closing || !started) return
        Logs.w("[olcrtc] session lost: $reason — starting reconnect")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var backoff = INITIAL_BACKOFF_MS
            while (attempt < MAX_RECONNECT_ATTEMPTS && !closing) {
                attempt++
                Logs.i("[olcrtc] reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS (backoff ${backoff}ms)")
                // TODO: уведомить нотификацию: "Reconnecting… ($attempt/$MAX_RECONNECT_ATTEMPTS)"
                try { Mobile.stop() } catch (_: Exception) {}
                delay(backoff)
                if (closing) break
                try {
                    startGoClient()
                    Logs.i("[olcrtc] reconnected successfully")
                    // TODO: вернуть нотификацию в "Connected"
                    return@launch   // успех — выходим
                } catch (e: Exception) {
                    Logs.w("[olcrtc] reconnect attempt $attempt failed: ${e.message}")
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            // исчерпали попытки
            Logs.e("[olcrtc] reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts")
            // TODO: пробросить ошибку наверх для stopRunner()
        }
    }

    // === close() — прерывает реконнект ===
    override fun close() {
        closing = true
        reconnectJob?.cancel()
        scope.cancel()
        if (!started) return
        try { Mobile.stop() } catch (e: Exception) { Logs.w(e) }
        finally { started = false }
    }
}
```

### 1.4. Проброс ошибки наверх

Сейчас `BaseService.Interface.onStartCommand()` ловит исключения из `startProcesses()` 
и вызывает `stopRunner()`. Но reconnect — асинхронный процесс. Нужен колбэк:

```kotlin
class OLCRTCExternalInstance(
    …
    private val onFatalError: (String) -> Unit = {},   // ← новый параметр
)
```

В `V2RayInstance.init()` при создании:
```kotlin
is OLCRTCBean -> {
    externalInstances[port] = OLCRTCExternalInstance(bean, port, username, password) { msg ->
        // Вызывается из IO-корутины при исчерпании попыток
        runOnMainDispatcher {
            // ProxyInstance → service → stopRunner
        }
    }
}
```

**Вопрос для обсуждения:** точный способ проброса `stopRunner()` из
`OLCRTCExternalInstance` — через лямбду, или через `interface ReconnectListener`,
или через broadcast. Лямбда — проще всего.

### 1.5. Нотификация «Reconnecting…»

`BaseService.Data.changeState()` принимает `msg: String?`. Можно добавить
промежуточный вызов:

```kotlin
// В OLCRTCExternalInstance, в reconnect-цикле:
onStatusChanged("Reconnecting… ($attempt/$MAX_RECONNECT_ATTEMPTS)")
```

Колбэк `onStatusChanged` пробрасывается аналогично `onFatalError` и вызывает
`data.notification?.update(msg)` или аналог. Нужно проверить API
`ServiceNotification` — скорее всего достаточно `notification.builder.setContentText(msg)`.

---

## 2. Keepalive-пинг

### 2.1. Проблема

WebRTC SFU отключает peer, если от него не приходит медиа / data в течение
таймаута (30–60 с у Telemost, до 120 с у Jazz). Когда пользователь не
генерирует трафик, olcRTC SOCKS-прокси простаивает, и SFU дропает сессию.

### 2.2. Требования

| # | Требование |
|---|-----------|
| K1 | `OLCRTCExternalInstance` запускает фоновую корутину, которая раз в N секунд отправляет keepalive-пакет через Go-клиент. |
| K2 | Интервал N настраивается пользователем в UI (по умолчанию 15 с). |
| K3 | Если keepalive не получает ответ — считаем сессию потерянной → `onSessionLost("keepalive timeout")`. |
| K4 | Keepalive останавливается при `close()`. |
| K5 | Keepalive-пакет не должен генерировать полезный трафик через SOCKS — это внутренний пинг Go-клиента. |

### 2.3. Go-сторона (нужен API в `mobile.Mobile`)

Предпочтительный вариант — **добавить в Go-библиотеку** метод:

```go
// mobile/mobile.go
func Ping() error {
    // Отправить небольшой пакет через DataChannel / transport
    // и дождаться echo. Таймаут 5 с.
}
```

Если это невозможно или слишком долго — **fallback-вариант на Kotlin-стороне**:
отправить 1 байт через локальный SOCKS5-порт на заведомо отвечающий адрес
(например, `HEAD http://1.1.1.1/` или TCP-connect к `1.1.1.1:53` с немедленным
закрытием). Это сгенерирует минимальный трафик через туннель и удержит сессию.

### 2.4. Kotlin-реализация (fallback без Go API)

```kotlin
// В OLCRTCExternalInstance:

private var keepaliveJob: Job? = null

private fun startKeepalive() {
    val intervalSec = bean.keepaliveIntervalSec.let { if (it <= 0) 15 else it }
    keepaliveJob = scope.launch {
        while (isActive && started && !closing) {
            delay(intervalSec * 1000L)
            if (closing || !started) break
            try {
                // TCP-connect через локальный SOCKS к 1.1.1.1:53
                // Если упал — сессия мертва
                val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress("1.1.1.1", 53), 5_000)
                socket.close()
                Logs.d("[olcrtc] keepalive OK")
            } catch (e: Exception) {
                Logs.w("[olcrtc] keepalive failed: ${e.message}")
                onSessionLost("keepalive failed: ${e.message}")
                break
            }
        }
    }
}

private fun stopKeepalive() {
    keepaliveJob?.cancel()
}
```

Вызовы:
- `startKeepalive()` — в конце `launch()` после `started = true`
- `stopKeepalive()` — в начале `close()` и в начале `onSessionLost()`
- Повторный `startKeepalive()` — после успешного реконнекта

### 2.5. Новое поле `keepaliveIntervalSec`

#### OLCRTCBean.java

```java
public int keepaliveIntervalSec;     // default 15

// initializeDefaultValues():
if (keepaliveIntervalSec <= 0) keepaliveIntervalSec = 15;

// serialize(): — bump version → 2
output.writeInt(2);
…
output.writeInt(keepaliveIntervalSec);

// deserialize():
if (version >= 2) {
    keepaliveIntervalSec = input.readInt();
}
```

#### Constants.kt
```kotlin
const val SERVER_OLCRTC_KEEPALIVE_INTERVAL = "serverOlcrtcKeepaliveInterval"
```

#### DataStore.kt
```kotlin
var serverOlcrtcKeepaliveInterval by profileCacheStore.stringToInt(Key.SERVER_OLCRTC_KEEPALIVE_INTERVAL)
```

#### OLCRTCSettingsActivity.kt
```kotlin
// init():
DataStore.serverOlcrtcKeepaliveInterval = keepaliveIntervalSec

// serialize():
keepaliveIntervalSec = DataStore.serverOlcrtcKeepaliveInterval.let { if (it <= 0) 15 else it }
```

#### olcrtc_preferences.xml
```xml
<EditTextPreference
    app:key="serverOlcrtcKeepaliveInterval"
    app:title="@string/olcrtc_keepalive_interval"
    android:inputType="number"
    android:defaultValue="15" />
```

#### strings.xml
```xml
<!-- EN -->
<string name="olcrtc_keepalive_interval">Keepalive interval (sec)</string>
<string name="olcrtc_keepalive_interval_summary">Send a ping every N seconds to keep the WebRTC session alive (0 = disabled)</string>

<!-- RU -->
<string name="olcrtc_keepalive_interval">Интервал keepalive (сек)</string>
<string name="olcrtc_keepalive_interval_summary">Отправлять пинг каждые N секунд, чтобы WebRTC-сессия не отваливалась (0 = отключено)</string>
```

#### OLCRTCFmt.kt — URI/JSON

URI: `olcrtc://carrier@room/id?key=…&transport=…&keepalive=15#name`

JSON:
```json
{
  …
  "keepalive_interval_sec": 15
}
```

При парсинге: если поле отсутствует → default 15.

---

## 3. Export логов одной кнопкой

### 3.1. Требования

| # | Требование |
|---|-----------|
| L1 | В `LogcatFragment` добавляется пункт меню **«Export olcRTC log»** (иконка share). |
| L2 | Экспортируются только строки с тегами: `Go`, `olcrtc`, `libsagernetcore`, `VpnService`, `Exclave`, `ProxyInstance`. |
| L3 | Перед экспортом из лога удаляются (заменяются на `[REDACTED]`) значения: room_id (числовая строка после `room/`), key_hex (64-символьная hex-строка), любые 64-hex подстроки. |
| L4 | Результат — текстовый файл, предлагается через `Intent.ACTION_SEND` (share sheet). |
| L5 | Также добавляется кнопка **«Copy to clipboard»** (без share, просто буфер). |
| L6 | В шапке файла — метаданные: версия приложения, версия Android, модель устройства, имя профиля, provider, transport, текущий state. |

### 3.2. Реализация

#### Санитизация секретов

```kotlin
object OlcrtcLogSanitizer {
    // 64-символьные hex-строки (key_hex)
    private val HEX_64 = Regex("[0-9a-fA-F]{64}")
    // Room ID после «room/» — числа
    private val ROOM_ID = Regex("(?<=room/)[0-9]+")

    fun sanitize(line: String): String {
        return line
            .replace(HEX_64, "[REDACTED-KEY]")
            .replace(ROOM_ID, "[REDACTED-ROOM]")
    }
}
```

#### Новый метод в `LogcatFragment.kt`

```kotlin
R.id.action_export_olcrtc_log -> {
    val context = requireContext()
    runOnDefaultDispatcher {
        val logFile = File.createTempFile(
            "olcRTC-log-",
            ".txt",
            File(app.externalCacheDir, "log").also { it.mkdirs() }
        )

        // Шапка
        val header = buildString {
            appendLine("=== olcRTC Log Export ===")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: ${java.time.Instant.now()}")
            appendLine("========================")
            appendLine()
        }
        logFile.writeText(header)

        // Фильтрованный logcat
        val tags = arrayOf(
            "Go:D", "v2ray-core:D", "libsagernetcore:D",
            "VpnService:D", "Exclave:D", "ProxyInstance:D",
            "GuardedProcessPool:D", "*:S"
        )
        try {
            val process = ProcessBuilder(
                listOf("logcat", "-d", "-v", "threadtime", "-s", tags.joinToString(","))
            ).start()
            process.inputStream.bufferedReader().useLines { lines ->
                logFile.appendText(
                    lines.map { OlcrtcLogSanitizer.sanitize(it) }
                        .joinToString("\n")
                )
            }
        } catch (e: IOException) {
            logFile.appendText("Export error: ${e.message}")
        }

        // Share
        val uri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".cache", logFile
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, uri),
                "Share olcRTC log"
            )
        )
    }
}
```

#### Копирование в буфер

```kotlin
R.id.action_copy_olcrtc_log -> {
    val text = binding.logsTextView.text.toString()
    val sanitized = text.lines()
        .map { OlcrtcLogSanitizer.sanitize(it) }
        .joinToString("\n")
    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("olcRTC log", sanitized))
    Toast.makeText(requireContext(), "Log copied (secrets redacted)", Toast.LENGTH_SHORT).show()
}
```

#### logcat_menu.xml — новые пункты

```xml
<item
    android:id="@+id/action_export_olcrtc_log"
    android:icon="@drawable/ic_baseline_share_24"
    android:title="@string/export_olcrtc_log"
    app:showAsAction="ifRoom" />

<item
    android:id="@+id/action_copy_olcrtc_log"
    android:icon="@drawable/ic_baseline_content_copy_24"
    android:title="@string/copy_olcrtc_log"
    app:showAsAction="never" />
```

#### strings.xml

```xml
<!-- EN -->
<string name="export_olcrtc_log">Export olcRTC log</string>
<string name="copy_olcrtc_log">Copy log (redacted)</string>

<!-- RU -->
<string name="export_olcrtc_log">Экспорт лога olcRTC</string>
<string name="copy_olcrtc_log">Копировать лог (без секретов)</string>
```

---

## 4. Интеграционная схема

```
┌──────────────────────────────────────────────────────┐
│ BaseService.onStartCommand()                         │
│   └─ ProxyInstance.launch()                          │
│       └─ V2RayInstance.launch()                      │
│           └─ OLCRTCExternalInstance.launch()          │
│               ├─ setupMobileCallbacks()              │
│               ├─ startGoClient()                     │
│               ├─ started = true                      │
│               └─ startKeepalive()  ◄── НОВОЕ         │
│                    │                                 │
│                    ▼ (каждые N сек)                   │
│               keepalive ping                         │
│                    │                                 │
│                    ├─ OK → продолжить                │
│                    └─ FAIL → onSessionLost()         │
│                              │                       │
│                              ▼                       │
│                         reconnectLoop()  ◄── НОВОЕ   │
│                              │                       │
│                              ├─ Mobile.stop()        │
│                              ├─ delay(backoff)       │
│                              ├─ startGoClient()      │
│                              ├─ OK → startKeepalive()│
│                              └─ FAIL (10x) →         │
│                                  onFatalError() →    │
│                                  stopRunner()        │
│                                                      │
│ close()                                              │
│   ├─ closing = true                                  │
│   ├─ stopKeepalive()                                 │
│   ├─ reconnectJob.cancel()                           │
│   └─ Mobile.stop()                                   │
└──────────────────────────────────────────────────────┘
```

---

## 5. Версионирование и совместимость

| Поле | v0 (old) | v1 (current) | v2 (new) |
|------|----------|-------------|----------|
| provider | ✓ | ✓ | ✓ |
| roomId | ✓ | ✓ | ✓ |
| keyHex | ✓ | ✓ | ✓ |
| dnsServer | ✓ | ✓ | ✓ |
| transport | — | ✓ | ✓ |
| vp8Fps | — | ✓ | ✓ |
| vp8BatchSize | — | ✓ | ✓ |
| keepaliveIntervalSec | — | — | ✓ |

Десериализация: `if (version >= 2) { keepaliveIntervalSec = input.readInt() }`  
Старые профили получат default 15 через `initializeDefaultValues()`.

---

## 6. Порядок реализации

| # | Задача | Приоритет |
|---|--------|-----------|
| 1 | `OLCRTCBean.java` — поле `keepaliveIntervalSec`, Kryo v2 | high |
| 2 | `Constants.kt` + `DataStore.kt` — новый ключ | high |
| 3 | `OLCRTCSettingsActivity.kt` — init/serialize keepalive | high |
| 4 | `olcrtc_preferences.xml` — EditTextPreference | high |
| 5 | `strings.xml` (EN + RU) — keepalive строки | high |
| 6 | `OLCRTCFmt.kt` — keepalive в URI/JSON | high |
| 7 | `OLCRTCExternalInstance.kt` — reconnect + keepalive | high |
| 8 | Проброс `onFatalError` / `onStatusChanged` через `V2RayInstance` | high |
| 9 | `OlcrtcLogSanitizer.kt` — утилита санитизации | medium |
| 10 | `LogcatFragment.kt` — export/copy пункты меню | medium |
| 11 | `logcat_menu.xml` — новые item'ы | medium |
| 12 | `strings.xml` (EN + RU) — строки экспорта | medium |
| 13 | `README-olcRTC.md` — обновить документацию | low |
| 14 | `version.properties` — bump | high |
| 15 | Build + release | high |

---

## 7. Тестирование

### Reconnect
- Подключиться → убить серверный процесс olcrtc → убедиться что клиент
  автоматически переподключается (до 10 попыток).
- Переключить сеть Wi-Fi → LTE при активном соединении → проверить реконнект.
- Закрыть VPN во время backoff → убедиться что цикл прерывается мгновенно.

### Keepalive
- Подключиться → не генерировать трафик 5 минут → убедиться что сессия жива.
- Установить `keepalive = 0` → убедиться что пинг не отправляется.
- Установить `keepalive = 5` → в логе видно `[olcrtc] keepalive OK` каждые 5 с.

### Export логов
- Открыть Logs → Export olcRTC log → убедиться что файл содержит только
  релевантные теги.
- Проверить что key_hex и room_id заменены на `[REDACTED-*]`.
- Copy to clipboard → вставить → проверить санитизацию.

---

## 8. Открытые вопросы

1. **Go API для пинга.** Идеально — метод `Mobile.Ping()` на Go-стороне
   (нулевой трафик через SFU, только internal echo). Если делать — нужен PR
   в `Olcrtc_manager`. Fallback через SOCKS-connect к `1.1.1.1:53` работает,
   но генерирует ~100 байт реального трафика на пинг.

2. **Уведомление при реконнекте.** Менять текст нотификации на «Reconnecting…»
   или добавить отдельный индикатор в UI? Нотификация проще.

3. **Взаимодействие с V2Ray reconnect.** Если у V2Ray есть собственный
   механизм обнаружения обрыва (outbound health check), он может среагировать
   раньше olcRTC-реконнекта. Нужно убедиться, что они не конфликтуют.
