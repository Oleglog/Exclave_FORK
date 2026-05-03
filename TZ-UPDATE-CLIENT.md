# ТЗ: Обновление клиента Exclave под новый сервер olcrtc

## Контекст

Upstream `openlibrecommunity/olcrtc` произвёл крупный рефакторинг:
- Свой мультиплексор → `smux` (несовместимый протокол)
- `provider` → `carrier` (переименование)
- `wb_stream` → `wbstream`
- Добавлены транспорты: `datachannel`, `videochannel`, `seichannel`, `vp8channel`
- Go mobile API: `StartWithProvider()` удалён → `Start()` с тем же набором аргументов

**Без обновления клиент не сможет подключиться к новому серверу.**

## Изменения в Go mobile API

### Было (старый `mobile/mobile.go`):
```go
func StartWithProvider(providerName, roomID, keyHex string, socksPort int, socksUser, socksPass string) error
```

### Стало (новый `mobile/mobile.go`):
```go
func Start(carrierName, roomID, keyHex string, socksPort int, socksUser, socksPass string) error
func StartWithTransport(carrierName, transportName, roomID, keyHex string, socksPort int, socksUser, socksPass string) error
func SetTransport(transport string)    // "datachannel" или "vp8channel"
func SetLink(link string)              // "direct"
func SetDNS(dnsServer string)
func SetVP8Options(fps, batchSize int)
func SetProviders()                    // регистрирует все carrier-ы
```

Аргументы `Start()` идентичны старому `StartWithProvider()` — просто имя функции изменилось.

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

### 2. `OLCRTCExternalInstance.kt` — вызов нового API

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
Mobile.setProviders()  // регистрирует carrier-ы, link-и, transport-ы
Mobile.start(
    bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST },
    bean.roomId,
    bean.keyHex,
    port.toLong(),
    username,
    password,
)
```

Вызов `Mobile.setProviders()` нужен один раз перед `start()`, потому что
в новом коде `registerDefaults()` вызывается через `session.RegisterDefaults()`.
Старый код регистрировал провайдеры автоматически. Новый — требует явного вызова
(либо `SetProviders()`, либо `Start()` сам вызывает `registerDefaults()` внутри).

> **Уточнение:** глядя в код `startWithConfig()` строка 179 — он сам вызывает
> `registerDefaults()`. Поэтому `Mobile.setProviders()` **не обязателен**,
> `Mobile.start()` справится сам. Но если хочется — можно вызвать заранее.

Итого минимальное изменение — одна строка:
```diff
- Mobile.startWithProvider(
+ Mobile.start(
```

### 3. `OLCRTCBean.java` — переименование провайдера

```diff
- public static final String PROVIDER_WB_STREAM = "wb_stream";
+ public static final String PROVIDER_WB_STREAM = "wbstream";
```

**Важно:** значение `"wb_stream"` тоже работает — в новом `mobile.go` функция
`normalizeCarrier()` пропускает `"wbstream"` как есть, а остальные возвращает
без изменений. Поэтому `"wb_stream"` **не будет** преобразовано в `"wbstream"`.
Нужно именно изменить значение константы.

Если нужна обратная совместимость с уже сохранёнными профилями:
```java
public static final String PROVIDER_WB_STREAM = "wbstream";
public static final String PROVIDER_WB_STREAM_LEGACY = "wb_stream";
```

### 4. `OLCRTCFmt.kt` — обновить валидацию

```diff
  private val VALID_PROVIDERS = setOf(
      OLCRTCBean.PROVIDER_TELEMOST,
      OLCRTCBean.PROVIDER_JAZZ,
      OLCRTCBean.PROVIDER_WB_STREAM,
+     "wb_stream",  // обратная совместимость со старыми URI
  )
```

В `parseOLCRTC` — нормализовать `wb_stream` → `wbstream`:
```diff
  fun parseOLCRTC(url: String): OLCRTCBean {
      val link = Libsagernetcore.parseURL(url)
      return OLCRTCBean().apply {
-         provider = link.username
+         provider = normalizeProvider(link.username)
          ...
      }
  }

+ private fun normalizeProvider(p: String): String = when (p) {
+     "wb_stream" -> OLCRTCBean.PROVIDER_WB_STREAM
+     else -> p
+ }
```

### 5. `app/src/main/res/values/arrays.xml` — значение в списке

```diff
  <string-array name="olcrtc_provider_value">
      <item>telemost</item>
      <item>jazz</item>
-     <item>wb_stream</item>
+     <item>wbstream</item>
  </string-array>
```

### 6. Миграция сохранённых профилей (опционально)

Пользователи со старыми профилями `wb_stream` не смогут подключиться.
Варианты:
- **Простой:** в `OLCRTCExternalInstance.launch()` подменять `"wb_stream"` → `"wbstream"`:
  ```kotlin
  val carrier = when (bean.provider) {
      "wb_stream" -> "wbstream"
      else -> bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST }
  }
  Mobile.start(carrier, ...)
  ```
- **Правильный:** миграция БД при апгрейде версии приложения

## Сборка нового AAR

После обновления `go.mod`:
```bash
cd library/core
gomobile bind -target=android -androidapi 21 -o ../../app/libs/libsagernetcore.aar .
```

Либо через gradle-задачу, если она настроена в проекте.

## Итого

| Файл | Изменение | Объём |
|------|-----------|-------|
| `library/core/go.mod` | Обновить хеш зависимости | 1 строка |
| `OLCRTCExternalInstance.kt` | `startWithProvider` → `start` | 1 строка |
| `OLCRTCBean.java` | `"wb_stream"` → `"wbstream"` | 1 строка |
| `OLCRTCFmt.kt` | Нормализация + валидация | ~10 строк |
| `arrays.xml` | `wb_stream` → `wbstream` | 1 строка |

**Обратная совместимость URI:** старые ссылки `olcrtc://wb_stream@room/...`
будут работать через нормализацию в `parseOLCRTC`.

**Критично:** клиент и сервер обновляются **одновременно** — протокол `smux`
не совместим со старым мультиплексором.
