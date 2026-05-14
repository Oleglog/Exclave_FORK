package config

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

const (
	testModeSrv      = "srv"
	testAuthProvider = "wbstream"
	testRoomID       = "r1"
	testCryptoKey    = "deadbeef"
)

func TestLoadAndApply(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "olcrtc.yaml")
	body := `
mode: srv
link: direct
auth:
  provider: wbstream
room:
  id: r1
crypto:
  key: deadbeef
net:
  transport: datachannel
  dns: 1.1.1.1:53
socks:
  host: 127.0.0.1
  port: 1080
  user: u
  pass: p
vp8:
  fps: 25
  batch_size: 4
gen:
  amount: 3
debug: true
`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	f, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	requireLoadedFile(t, f)

	got := Apply(session.Config{}, f)
	requireAppliedConfig(t, got)
}

func requireLoadedFile(t *testing.T, f File) {
	t.Helper()
	if f.Mode != testModeSrv {
		t.Fatalf("Mode = %q, want %q", f.Mode, testModeSrv)
	}
	if f.Auth.Provider != testAuthProvider {
		t.Fatalf("Auth.Provider = %q, want %q", f.Auth.Provider, testAuthProvider)
	}
	if f.Room.ID != testRoomID {
		t.Fatalf("Room.ID = %q, want %q", f.Room.ID, testRoomID)
	}
	if f.Crypto.Key != testCryptoKey {
		t.Fatalf("Crypto.Key = %q, want %q", f.Crypto.Key, testCryptoKey)
	}
}

func requireAppliedConfig(t *testing.T, got session.Config) {
	t.Helper()
	want := session.Config{
		Mode:         testModeSrv,
		Link:         "direct",
		Auth:         testAuthProvider,
		RoomID:       testRoomID,
		KeyHex:       testCryptoKey,
		Transport:    "datachannel",
		DNSServer:    "1.1.1.1:53",
		SOCKSHost:    "127.0.0.1",
		SOCKSPort:    1080,
		SOCKSUser:    "u",
		SOCKSPass:    "p",
		VP8FPS:       25,
		VP8BatchSize: 4,
		Amount:       3,
	}
	if got != want {
		t.Fatalf("Apply produced wrong config: %+v, want %+v", got, want)
	}
}

func TestApplyCLIWins(t *testing.T) {
	cli := session.Config{
		Mode:      "cnc",
		KeyHex:    "from-cli",
		SOCKSPort: 9999,
	}
	f := File{
		Mode:   testModeSrv,
		Crypto: Crypto{Key: "from-yaml"},
		SOCKS:  SOCKS{Port: 1234, Host: "0.0.0.0"},
	}
	got := Apply(cli, f)
	if got.Mode != "cnc" {
		t.Errorf("Mode: got %q, want cnc (CLI wins)", got.Mode)
	}
	if got.KeyHex != "from-cli" {
		t.Errorf("KeyHex: got %q, want from-cli (CLI wins)", got.KeyHex)
	}
	if got.SOCKSPort != 9999 {
		t.Errorf("SOCKSPort: got %d, want 9999 (CLI wins)", got.SOCKSPort)
	}
	if got.SOCKSHost != "0.0.0.0" {
		t.Errorf("SOCKSHost: got %q, want 0.0.0.0 (YAML fills empty CLI)", got.SOCKSHost)
	}
}

func TestLoadMissing(t *testing.T) {
	_, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}
