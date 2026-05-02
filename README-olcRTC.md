# olcRTC for Android

A fork of [Exclave](https://github.com/dyhkwong/Exclave) that integrates
[olcrtc](https://github.com/openlibrecommunity/olcrtc) as a first-class proxy
type. olcrtc tunnels TCP/UDP traffic over WebRTC through whitelisted services
(Yandex Telemost, SaluteJazz, Wildberries Stream) so that traffic is hard to
block without breaking those services.

> This is an Android-only client fork. The olcrtc server is a separate project
> and is not modified here.

## Status

- [x] olcrtc/mobile bound into the same `libsagernetcore.aar` (single Go
      runtime, no `libgojni.so` collision)
- [x] New protocol type `TYPE_OLCRTC = 30` with Kryo-serialised `OLCRTCBean`
      (provider / roomId / keyHex / DNS server)
- [x] Settings screen + entry in *Add new profile*
- [x] Lifecycle wired through `V2RayInstance` / `OLCRTCExternalInstance`
      (V2Ray points at the local SOCKS5 listener exposed by olcrtc)
- [x] App rebrand: `applicationId` = `community.openlibre.olcrtc.android`,
      app name = `olcRTC`, fresh release keystore (not the upstream one)
- [x] Localised strings (en + ru)
- [x] Debug + release APKs build for `arm64-v8a`, `armeabi-v7a`, `x86`,
      `x86_64`

## Download

Pre-built APKs are attached to the [GitHub
releases](https://github.com/Oleglog/Exclave_FORK/releases) of this fork.
For most phones you want `olcRTC-<version>-arm64-v8a.apk`.

The release APK is signed with a fresh self-signed key generated for this
fork — **the signing certificate fingerprint is different from the upstream
Exclave key**, so this app cannot be installed on top of an existing Exclave
install (and vice versa). That is intentional.

## Setting up the olcrtc server

1. Clone https://github.com/openlibrecommunity/olcrtc on a publicly reachable
   host.
2. Generate a 32-byte hex key:

   ```bash
   openssl rand -hex 32
   ```

3. Pick a provider (`telemost`, `jazz`, or `wb_stream`) and a fresh `roomId`
   for that provider (start a meeting in the chosen service and copy the
   conference id from its URL).
4. Run the server, for example with `docker-compose.server.yml` from the
   olcrtc repo. Make sure it can reach the chosen TURN/ICE servers from
   inside the container.
5. Pass the same `keyHex` and `roomId` to the Android app.

## Setting up the Android app

1. Install `olcRTC-*-arm64-v8a.apk` from the release page.
2. Open the app, go to *Add new profile* → **olcRTC**.
3. Fill:
   - *Provider*: `telemost`, `jazz`, or `wb_stream`.
   - *Room ID*: same value as on the server.
   - *Key (hex)*: same 64-character hex string as on the server.
   - *DNS server*: optional; falls back to the system resolver.
4. Save the profile, set it as active, hit *Connect*.
5. The first connection takes up to ~15 s while the WebRTC peer connection is
   negotiated; subsequent reconnects are faster.

## Building from source

Requirements:

- JDK 21
- Android SDK Platform 36, Build-Tools 37.0.0
- Android NDK r29 (29.0.14206865)
- Go 1.25 + `gomobile` (only required to rebuild `libsagernetcore.aar`)

```bash
# 1. Build the merged Go AAR (libsagernetcore + olcrtc/mobile)
bin/lib/core/build.sh

# 2. Assemble the debug APK
./gradlew :app:assembleOssDebug

# 3. Assemble the signed release APK (uses release.keystore + the
#    KEYSTORE_PASS / ALIAS_NAME / ALIAS_PASS values in local.properties)
./gradlew :app:assembleOssRelease
```

APKs land in `app/build/outputs/apk/oss/{debug,release}/`.

## Why a single AAR

Both the upstream v2ray-based `libsagernetcore` and `olcrtc/mobile` are
gomobile bindings, and gomobile produces an AAR with its own
`go.*` Java classes plus a `libgojni.so` per ABI. Two such AARs in the same
APK collide:

```
Duplicate class go.Seq found in modules libsagernetcore.aar and olcrtc.aar
```

The fix in this fork is to import `github.com/openlibrecommunity/olcrtc/mobile`
into the same Go module that produces `libsagernetcore.aar` and to call
`gomobile bind` with both packages on the command line. The result is a
single AAR that exports both `libsagernetcore.*` and `mobile.*` Java
packages and a single `libgojni.so` per ABI.

See `library/core/main.go` and `library/core/build.sh`.

## Licenses

- This fork inherits **GNU GPLv3** from Exclave (`./LICENSE`).
- olcrtc itself is **WTFPL**, which is GPLv3-compatible.
- See the upstream `README.md` for the v2ray / SagerNet credit chain.

## Acknowledgements

- [dyhkwong/Exclave](https://github.com/dyhkwong/Exclave) — base proxy client.
- [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc) —
  WebRTC tunnel server and Go client.
- [SagerNet](https://github.com/SagerNet/SagerNet) — original Android proxy
  framework Exclave is forked from.
