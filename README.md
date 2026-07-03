# olcRTC Android Client

Android-клиент для [olcRTC server](https://github.com/Oleglog/Olcrtc_manager), основанный на Exclave/SagerNet.

## Что это

Приложение добавляет профиль **olcRTC** и поднимает системный VPN на Android. Трафик приложений идёт через локальный SOCKS5 в Go-клиент olcRTC, затем через WebRTC carrier к серверу на VPS.

Поддерживаемые provider:

```text
jitsi, telemost, wbstream
```

Поддерживаемые transport:

```text
datachannel, vp8channel, seichannel, videochannel
```

Рекомендуемый default:

```text
transport = vp8channel
```

## Актуальная совместимость

```text
Android: olcrtc-2.0.22+
Server:  server-v1.9.31+
```

Важно: для `vp8channel` сервер и клиент должны быть обновлены вместе, потому что в релизах `server-v1.9.27 / olcrtc-2.0.17` добавлен CRC trailer для KCP packets.

## Установка

Скачай APK из GitHub Releases:

```text
https://github.com/Oleglog/Exclave_olcrtc/releases
```

Для большинства телефонов нужен:

```text
olcRTC-<version>-arm64-v8a.apk
```

Для старых 32-bit устройств:

```text
olcRTC-<version>-armeabi-v7a.apk
```

## Импорт профиля

Самый удобный способ:

1. Открой Admin UI сервера.
2. Создай или открой инстанс.
3. Нажми QR.
4. Отсканируй QR в приложении.

QR может включать `auth_token` для WB Stream, поэтому считай QR секретом.

## WB Stream auth.token

Для `wbstream`, особенно для `datachannel`, может понадобиться аккаунтный/модераторский WB token. Приложение умеет импортировать `auth_token` из QR/URI и хранить его в профиле.

## Подписки

Если subscription URL использует самоподписанный сертификат или находится вне маршрутов активного VPN, включи настройку:

```text
allowInsecureOnRequest
```

В этой версии при включённом `allowInsecureOnRequest` обновление подписки идёт напрямую и отключает TLS-проверку для subscription request.

## Подпись APK

Release APK должен быть подписан постоянным ключом, иначе Android не сможет обновлять приложение поверх старой установки.

GitHub Actions release build использует secrets:

```text
RELEASE_KEYSTORE_BASE64
KEYSTORE_PASS
ALIAS_NAME
ALIAS_PASS
```

Создание ключа:

```bash
keytool -genkeypair   -v   -keystore release.keystore   -alias olcrtc   -keyalg RSA   -keysize 4096   -validity 10000
```

Добавить keystore в GitHub Secret:

```bash
base64 -w0 release.keystore
```

Значение положить в `RELEASE_KEYSTORE_BASE64`, пароли и alias в остальные secrets.

Не коммить `release.keystore` в репозиторий.

## Сборка

```bash
./run lib core
./gradlew :app:assembleOssDebug
```

Release build локально:

```bash
# local.properties должен содержать KEYSTORE_PASS, ALIAS_NAME, ALIAS_PASS
# release.keystore должен лежать в корне репозитория
./gradlew :app:assembleOssRelease
```

Подробности: `README-olcRTC.md` и `BUILD.md`.

## License

GPLv3, как Exclave/SagerNet. olcRTC Go-core использует свою upstream-лицензию.
