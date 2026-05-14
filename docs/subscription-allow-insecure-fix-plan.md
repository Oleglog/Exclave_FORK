# План исправления: `allowInsecureOnRequest` при обновлении подписок

## Краткое описание проблемы

Даже при включённых настройках **«Всегда разрешать небезопасные»** (`globalAllowInsecure`) и **«Отключить проверку сертификатов при обновлении подписок»** (`allowInsecureOnRequest`) обновление подписки по HTTPS с самоподписанным сертификатом (например, `https://89.125.93.65:3000/sub/…`) не работает без стороннего VPN.

## Причины (текущее состояние кода)

Анализ `SubscriptionHttpClient.kt`, `http.go` и связанных компонентов показал, что проблема состоит из трёх взаимосвязанных недочётов:

### 1. `fetchViaJava` полностью игнорирует `allowInsecureOnRequest`

Когда Exclave VPN **не запущен**, `SubscriptionHttpClient.fetch()` сначала пытается выполнить запрос через `fetchViaJava()`, который использует стандартный `HttpURLConnection`.

Этот Java-клиент **всегда** проверяет TLS-сертификат строго, и при самоподписанном сертификате неизбежно падает с `SSLHandshakeException`. Настройка `DataStore.allowInsecureOnRequest` в `fetchViaJava()` никак не учитывается.

### 2. Fallback-логика маскирует реальную ошибку

Если `fetchViaJava()` падает, код ловит исключение и пробует `fetchViaGo(useProxy = false)` — здесь `allowInsecure()` **должен** сработать.

Однако если `fetchViaGo()` тоже падает (хотя бы по любой причине — таймаут, сетевая блокировка, др.), пользователю показывается **исходное** исключение от Java-клиента (`throw javaEx`), а не реальная причина от Go-клиента. Это делает диагностику невозможной и вводит в заблуждение.

```kotlin
} catch (goEx: Exception) {
    Logs.w("Go HTTP also failed: ${goEx.message}")
    throw javaEx  // ← пользователь видит SSL ошибку от Java, а не от Go
}
```

### 3. При `useProxy = true` флаг `allowInsecure()` не доходит до целевого сервера

Когда Exclave VPN **запущен** (`connected == true`), `fetchViaGo()` вызывается с `useProxy = true`. В этом режиме Go HTTP-клиент соединяется не напрямую с сервером подписки, а через UDS (Unix Domain Socket) с локальным прокси-ядром (V2Ray/Xray/sing-box).

- `allowInsecure()` устанавливает `InsecureSkipVerify = true` в `tls.Config` Go-клиента.
- Но TLS-соединение с сервером подписки устанавливается уже **внутри прокси-ядра**, а не в Go HTTP-клиенте.
- Go-клиент говорит прокси: «открой мне TCP-туннель к 89.125.93.65:3000».
- Прокси сам выполняет TLS-рукопожатие и строго проверяет сертификат, не зная о настройке `allowInsecureOnRequest`.
- Поэтому при включённом VPN Exclave подписка всё равно падает по TLS, даже если `allowInsecure()` вызван.

### Почему работает со сторонним VPN

Сторонний VPN (например, системный WireGuard/OpenVPN) обычно работает на уровне маршрутизации и может:
- либо подменять/пропускать TLS-трафик (если провайдер блокирует IP),
- либо провайдер сам возвращает валидный сертификат (MITM),
- либо просто позволяет достучаться до хоста, который без VPN режется по IP.

В любом случае это побочный эффект работы стороннего VPN, а не корректная работа флага `allowInsecureOnRequest`.

---

## План исправления

### Шаг 1. Добавить поддержку `allowInsecureOnRequest` в `fetchViaJava()`

Когда `DataStore.allowInsecureOnRequest == true`, `fetchViaJava()` должен использовать кастомный `SSLContext`, который доверяет любому сертификату, и `HostnameVerifier`, который пропускает любой хост.

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionHttpClient.kt`

**Что добавить:**

```kotlin
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

private fun createInsecureSSLContext(): SSLContext {
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    })
    return SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }
}
```

**Что изменить в `fetchViaJava()`:**

```kotlin
private fun fetchViaJava(link: String, ua: String): SubscriptionResponse {
    val url = URL(link)
    val conn = url.openConnection() as HttpURLConnection
    try {
        if (DataStore.allowInsecureOnRequest && conn is HttpsURLConnection) {
            conn.sslSocketFactory = createInsecureSSLContext().socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", ua)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        // ... остальной код без изменений
    } finally {
        conn.disconnect()
    }
}
```

### Шаг 2. Не использовать локальный прокси при `allowInsecureOnRequest`

При включённом флаге `allowInsecureOnRequest` обновление подписки должно идти **напрямую** (`useProxy = false`), даже если VPN Exclave запущен. Иначе TLS-проверка всё равно выполняется прокси-ядром, которое не знает об этой настройке.

**Файл:** `app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionHttpClient.kt`

**Что изменить в `fetch()`:**

```kotlin
fun fetch(link: String, customUserAgent: String): SubscriptionResponse {
    val ua = customUserAgent.ifEmpty { USER_AGENT }
    val connected = SagerNet.started && DataStore.startedProfile > 0

    // Если пользователь явно разрешил небезопасные соединения для подписок,
    // идём напрямую через Go-клиент (или Java), минуя локальный прокси,
    // чтобы allowInsecure() реально отключал проверку сертификата целевого сервера.
    if (DataStore.allowInsecureOnRequest) {
        return try {
            fetchViaGo(link, ua, useProxy = false)
        } catch (goEx: Exception) {
            Logs.w("Go HTTP failed, trying Java client: ${goEx.message}")
            fetchViaJava(link, ua)  // теперь Java тоже поддерживает allowInsecure
        }
    }

    if (connected) {
        return fetchViaGo(link, ua, useProxy = true)
    }

    return try {
        fetchViaJava(link, ua)
    } catch (javaEx: Exception) {
        Logs.w("Java HTTP failed, trying Go client: ${javaEx.message}")
        fetchViaGo(link, ua, useProxy = false)
    }
}
```

> **Примечание:** в оригинальном fallback при падении `fetchViaGo` бросалось `javaEx`. Это исправлено — теперь при `allowInsecureOnRequest` основной путь через Go, а Java — fallback. Если же `allowInsecureOnRequest` выключен, логика остаётся прежней (Java → Go), но можно дополнительно рассмотреть бросание составного исключения, чтобы не терять диагностику.

### Шаг 3. Улучшить логирование и диагностику

Добавить явные логи о том, какой именно транспорт используется:

```kotlin
Logs.d("Subscription fetch: allowInsecure=${DataStore.allowInsecureOnRequest}, " +
       "connected=$connected, link=${link.take(30)}...")
```

Это позволит при тестировании сразу понимать, пошёл запрос через прокси или напрямую.

### Шаг 4. Проверить актуальность Go-модуля `libsagernetcore.aar`

Убедиться, что `library/core/libsagernetcore.aar` и `app/libs/libsagernetcore.aar` собраны из кода, который содержит метод `AllowInsecure()` (файл `library/core/libsagernetcore/http.go`, строки 161–163).

- Дата сборки AAR должна быть не раньше даты изменения `http.go`.
- Если изменения вносились в Go-код, необходимо пересобрать AAR через `library/core/build.sh` (или `build.bat` на Windows) и скопировать результат в `app/libs/`.

> На момент анализа метод `AllowInsecure()` в `http.go` реализован корректно (`r.tls.InsecureSkipVerify = true`), и `transport.TLSClientConfig` указывает на тот же `tls.Config`. Проблема не в Go-реализации, а в том, что при `useProxy = true` этот TLS-контекст просто не используется для соединения с целевым сервером.

---

## Сводка изменений

| Компонент | Файл | Действие |
|-----------|------|----------|
| HTTP-клиент (Java) | `SubscriptionHttpClient.kt` | Добавить `createInsecureSSLContext()` и применять в `fetchViaJava()` при `allowInsecureOnRequest` |
| Логика выбора транспорта | `SubscriptionHttpClient.kt` | При `allowInsecureOnRequest` использовать `useProxy = false`, даже если VPN Exclave запущен |
| Fallback / UX | `SubscriptionHttpClient.kt` | Не маскировать `goEx` под `javaEx`; логировать используемый транспорт |
| Сборка | `library/core/build.sh` | Убедиться, что AAR содержит актуальный `AllowInsecure()` |

---

## Ожидаемый результат после исправления

1. **VPN Exclave выключен, `allowInsecureOnRequest` включён:**
   - `fetchViaGo(useProxy = false)` выполняет запрос напрямую с `InsecureSkipVerify = true`.
   - Если Go-клиент по какой-то причине недоступен, fallback на `fetchViaJava()` тоже пропускает самоподписанный сертификат.
   - Подписка обновляется успешно.

2. **VPN Exclave включён, `allowInsecureOnRequest` включён:**
   - Запрос **не** идёт через локальный прокси, а напрямую через Go HTTP-клиент с отключённой проверкой сертификата.
   - Подписка обновляется успешно.

3. **Оба флага выключены:**
   - Поведение не меняется: при включённом VPN — через прокси, при выключенном — Java → Go fallback со строгой проверкой TLS.

---

## Замечания по безопасности

- Настройка `allowInsecureOnRequest` распространяется **только** на HTTP-запросы при обновлении подписок и не влияет на TLS-соединения ядра прокси (которые контролируются `globalAllowInsecure` через `ConfigBuilder.kt`).
- Кастомный `TrustManager`, доверяющий всем сертификатам, должен применяться **строго** под условием `DataStore.allowInsecureOnRequest`.
