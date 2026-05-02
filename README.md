# olcRTC for Android (fork of Exclave)

> **Android-клиент для [olcRTC](https://github.com/Oleglog/olcrtc_FORK)** —
> туннеля, прячущего трафик внутри WebRTC-сессий публичных российских
> видеоконференций (Wildberries Stream / Yandex Telemost / SaluteJazz).
>
> **Android client for [olcRTC](https://github.com/Oleglog/olcrtc_FORK)** —
> a tunnel hiding traffic inside WebRTC sessions of public Russian video-
> conferencing services (Wildberries Stream / Yandex Telemost / SaluteJazz).
>
> Подробная документация по форку, гайд по серверу, сборке APK и
> использованию: **[`README-olcRTC.md`](./README-olcRTC.md)** (RU + EN).
>
> Полные настройки сервера: [Oleglog/olcrtc_FORK](https://github.com/Oleglog/olcrtc_FORK).
>
> Готовые APK: [GitHub releases](https://github.com/Oleglog/Exclave_FORK/releases)
> (нужен `olcRTC-<version>-arm64-v8a.apk` для большинства телефонов).
>
> Этот форк основан на [dyhkwong/Exclave](https://github.com/dyhkwong/Exclave),
> upstream README которого приведён ниже для справки.

---

# Exclave

Exclave is a proxy client.

<details>

Features:

- Various proxy protocols
- Group and subscription
- Routing
- Proxy chain

Some supported protocols:

- Shadowsocks (with SIP003 plugin support)
- Shadowsocks 2022 (with SIP003 plugin support)
- Trojan
- Hysteria 2
- AnyTLS
- mieru
- NaïveProxy (as a standalone plugin)
- TUIC
- Juicity
- VMess (with various optional sub-protocols)
- VLESS (with various optional sub-protocols)
- WireGuard (TCP and UDP only)
- TrustTunnel (no ICMP echo support)
- SSH proxy ("dynamic port forwarding")
- HTTP CONNECT tunnel (HTTP/1.1, HTTP/1.1 with TLS, HTTP/2 and HTTP/3)
- SOCKS4, SOCKS4A and SOCKS5

</details>

It is a fork of the archived Android proxy client SagerNet and uses a custom overhauled fork of V2Ray.

## Download

[![GitHub releases](https://img.shields.io/badge/-GitHub%20Releases-7B68EE.svg?style=flat-square&logo=github)](https://github.com/dyhkwong/Exclave/releases)

## Build

[![Workflow status](https://img.shields.io/github/actions/workflow/status/dyhkwong/Exclave/build.yml?branch=dev&style=flat-square)](https://github.com/dyhkwong/Exclave/actions/workflows/build.yml?query=branch%3Adev) [![Latest commit on dev branch](https://img.shields.io/github/last-commit/dyhkwong/Exclave/dev?style=flat-square)](https://github.com/dyhkwong/Exclave/tree/dev)

## Translation

[![Translation status](https://hosted.weblate.org/widget/exclave/multi-auto.svg)](https://hosted.weblate.org/engage/exclave/)

[Hosted Weblate](https://hosted.weblate.org/projects/exclave/)

## Issues

Old versions are not supported. Please ensure you are using the latest version.

Crash reports require debug-level logs.

[Encrypt the report files with the following GPG public key.](https://github.com/dyhkwong.gpg)

## License

```
Copyright (C) 2023  dyhkwong
Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
