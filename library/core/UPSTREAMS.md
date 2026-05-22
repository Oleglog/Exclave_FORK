# Upstream source update workflow

`olcrtc_local` and `vk_turn_proxy_local` are vendor copies used by `go.mod`
`replace` directives. They are intentionally built into the same
`libsagernetcore.aar` as the base core.

To update both upstreams and rebuild the AAR:

```bat
library\core\update-upstreams.bat
```

To update without rebuilding:

```bat
library\core\update-upstreams.bat -NoBuild
```

To pin a branch or tag:

```bat
library\core\update-upstreams.bat -OlcrtcRef main -VkTurnRef main
```

The script mirrors `openlibrecommunity/olcrtc` directly. For
`netzgiest/vk-turn-proxy`, it mirrors upstream but preserves the Android
adapter files that make the CLI client usable from gomobile:

- `client/main.go`
- `client/wrap.go`
- `client/manual_captcha.go`
- `client/ish_listener_*.go`
- `client/*_test.go`
- `mobile/mobile.go`

This is meant for small upstream changes. If upstream changes one of those
adapter files significantly, update manually by diffing the new upstream file
against the preserved local file, then rebuild with `build.bat`.
