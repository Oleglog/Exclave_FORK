# ТЗ: Внедрение globalAllowInsecure и allowInsecureOnRequest

## 1. Цель

Добавить две глобальные настройки безопасности:

1. **«Всегда разрешать небезопасные»** (`globalAllowInsecure`) — глобально применяет `allowInsecure=true` ко всем серверам при генерации V2Ray-конфига, независимо от настроек отдельного профиля.
2. **«Отключить проверку сертификатов при обновлении подписок»** (`allowInsecureOnRequest`) — отключает TLS-валидацию при HTTP-запросах на скачивание подписок.

## 2. Общий принцип

- `globalAllowInsecure` работает на уровне Kotlin/Java: при построении JSON-конфига в `ConfigBuilder.kt` условие `bean.allowInsecure` расширяется до `bean.allowInsecure || DataStore.globalAllowInsecure`.
- `allowInsecureOnRequest` работает на уровне HTTP-клиента. Поскольку приложение использует Go-модуль (`libsagernetcore`) для HTTP-запросов через gomobile bind, в Go-интерфейсе HTTP-запроса необходимо добавить метод `AllowInsecure()`, который устанавливает `tls.InsecureSkipVerify = true`. После этого метод вызывается из Kotlin перед выполнением запроса на обновление подписки.

---

## 3. Часть 1. Глобальная настройка `globalAllowInsecure`

### 3.1. Ключи настроек

В `app/src/main/java/io/nekohasekai/sagernet/Constants.kt` в объект `Key` добавить:

```kotlin
const val GLOBAL_ALLOW_INSECURE = "globalAllowInsecure"
const val ALLOW_INSECURE_ON_REQUEST = "allowInsecureOnRequest"
```

### 3.2. DataStore

В `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` добавить свойства (в секцию протокольных или глобальных настроек):

```kotlin
var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }
var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST) { false }
```

### 3.3. Экран настроек

В `app/src/main/res/xml/global_preferences.xml` внутри подходящей категории добавить два переключателя:

```xml
<SwitchPreference
    app:icon="@drawable/ic_action_lock_open"
    app:key="globalAllowInsecure"
    app:title="@string/global_allow_insecure" />

<SwitchPreference
    app:key="allowInsecureOnRequest"
    app:title="@string/allow_insecure_on_request_sum" />
```

### 3.4. Строковые ресурсы

В `app/src/main/res/values/strings.xml`:

```xml
<string name="global_allow_insecure">Always allow insecure</string>
<string name="allow_insecure_on_request_sum">Disable certificate checking when updating subscriptions</string>
```

В `app/src/main/res/values-ru/strings.xml`:

```xml
<string name="global_allow_insecure">Всегда разрешать небезопасные</string>
<string name="allow_insecure_on_request_sum">Отключить проверку сертификатов при обновлении подписок</string>
```

*(По необходимости добавить аналогичные строки в другие языковые файлы.)*

### 3.5. Применение при построении конфига

В `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` найти все вхождения проверки `bean.allowInsecure` и расширить условие:

```kotlin
if (bean.allowInsecure || DataStore.globalAllowInsecure) {
    allowInsecure = true
}
```

**Места, подлежащие изменению** (актуальны на момент написания ТЗ):

- TLS-настройки для `StandardV2RayBean` (VMess / VLESS / Trojan).
- TLS-настройки для `Hysteria2Bean`.
- TLS-настройки для `Tuic5Bean`.
- TLS-настройки для `Http3Bean`.
- TLS-настройки для `AnyTLSBean`.
- TLS-настройки для `JuicityBean`.
- TLS-настройки для `ShadowTLSBean`.
- TLS-настройки для `TrustTunnelBean`.

> **Важно:** использовать поиск по фрагменту `if (bean.allowInsecure)` внутри `ConfigBuilder.kt`, чтобы не пропустить новые outbound-типы, добавленные после написания данного ТЗ.

---

## 4. Часть 2. Отключение проверки сертификатов при обновлении подписок (`allowInsecureOnRequest`)

### 4.1. Общая логика

При выполнении HTTP-запроса на обновление подписки (`SubscriptionHttpClient.kt`) необходимо:

1. Проверить значение `DataStore.allowInsecureOnRequest`.
2. Если оно `true`, вызвать метод `AllowInsecure()` у Go-объекта HTTP-запроса перед выполнением.

Для этого требуется, чтобы Go-модуль `libsagernetcore` экспортировал данный метод.

### 4.2. Патч Go-модуля (`libsagernetcore`)

В форке модуля `github.com/dyhkwong/libsagernetcore` (или в локальном дереве, если оно vendored) необходимо изменить код HTTP-клиента.

**Шаг 1. Интерфейс HTTP-запроса**

Добавить метод в интерфейс `HTTPRequest`:

```go
type HTTPRequest interface {
    SetURL(link string) error
    SetMethod(method string)
    SetHeader(key string, value string)
    SetContent(content []byte)
    SetContentString(content string)
    SetUserAgent(userAgent string)
    AllowInsecure() // <-- новый метод
    Execute() (HTTPResponse, error)
}
```

**Шаг 2. Реализация**

Добавить реализацию для структуры `httpRequest`:

```go
func (r *httpRequest) AllowInsecure() {
    r.tls.InsecureSkipVerify = true
}
```

> Метод должен изменять поле `tls` (тип `tls.Config`), которое используется транспортом `http.Transport` данного клиента.

**Шаг 3. Сборка**

После внесения изменений пересобрать AAR:

```bash
# library/core/build.sh (или build.bat на Windows)
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
gomobile bind -v -androidapi 21 -trimpath \
  -ldflags="-s -buildid= -checklinkname=0" \
  -tags="with_clash" \
  -o libsagernetcore.aar \
  "github.com/dyhkwong/libsagernetcore" \
  "github.com/openlibrecommunity/olcrtc/mobile"
```

Результат скопировать в `app/libs/libsagernetcore.aar`.

### 4.3. Использование в Kotlin

В `app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionHttpClient.kt` изменить метод `fetchViaGo`:

```kotlin
private fun fetchViaGo(link: String, ua: String, useProxy: Boolean): SubscriptionResponse {
    val response = Libsagernetcore.newHttpClient().apply {
        if (useProxy) {
            useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
        }
    }.newRequest().apply {
        if (DataStore.allowInsecureOnRequest) {
            allowInsecure()
        }
        setURL(link)
        setUserAgent(ua)
    }.execute()

    val headers = mutableMapOf<String, String>()
    val subInfo = response.getHeader("Subscription-Userinfo")
    if (subInfo.isNotEmpty()) {
        headers["Subscription-Userinfo"] = subInfo
    }
    return SubscriptionResponse(response.contentString, headers)
}
```

> **Примечание:** метод `allowInsecure()` станет доступен в Java/Kotlin API автоматически после `gomobile bind`, так как gomobile экспортирует публичные методы Go-структур. Имя метода в Java будет `allowInsecure()` (Go-метод `AllowInsecure` преобразуется в camelCase). Если сгенерированный Java-интерфейс именует его иначе, следует ориентироваться на содержимое пакета `libsagernetcore` после сборки.

---

## 5. Тестирование

### 5.1. globalAllowInsecure

1. Создать профиль с отключённым `allowInsecure` (в настройках профиля).
2. Включить `globalAllowInsecure` в глобальных настройках.
3. Запустить соединение и проверить сгенерированный конфиг (через Debug-лог или дамп).
4. Ожидаемый результат: в блоке `tlsSettings` / `tls` outbound появляется `"allowInsecure": true`.

### 5.2. allowInsecureOnRequest

1. Добавить подписку с URL, использующим самоподписанный или просроченный TLS-сертификат.
2. При выключенной настройке обновление должно завершаться ошибкой TLS.
3. Включить `allowInsecureOnRequest`.
4. Повторно запустить обновление — подписка должна скачаться успешно.

---

## 6. Сводка изменений

| Компонент | Файл | Действие |
|-----------|------|----------|
| Ключи | `Constants.kt` | Добавить `GLOBAL_ALLOW_INSECURE`, `ALLOW_INSECURE_ON_REQUEST` |
| Хранилище | `DataStore.kt` | Добавить `globalAllowInsecure`, `allowInsecureOnRequest` |
| UI | `global_preferences.xml` | Добавить 2 `SwitchPreference` |
| Локализация | `strings.xml` (en, ru, др.) | Добавить строки |
| Конфиг | `ConfigBuilder.kt` | `bean.allowInsecure` → `bean.allowInsecure \|\| DataStore.globalAllowInsecure` |
| HTTP-клиент | `SubscriptionHttpClient.kt` | Вызов `allowInsecure()` при `DataStore.allowInsecureOnRequest` |
| Go-модуль | `libsagernetcore` (внешний форк) | Добавить `AllowInsecure()` в интерфейс и реализацию HTTP-запроса |
| Сборка | `library/core/build.sh` | Пересобрать `libsagernetcore.aar` с изменениями |

---

## 7. Замечания по безопасности

- Включение `globalAllowInsecure` делает все TLS-соединения уязвимыми для MITM-атак. Рекомендуется использовать только в контролируемых средах.
- `allowInsecureOnRequest` распространяется только на HTTP-запросы подписок и не влияет на TLS-соединения ядра прокси.
