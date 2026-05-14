package session

import (
	"context"
	"errors"
	"testing"
)

//nolint:maintidx // table-driven validation test naturally has many cases
func TestValidate(t *testing.T) {
	RegisterDefaults()

	base := Config{
		Mode:      modeSRV,
		Link:      "direct",
		Transport: "datachannel",
		Auth:      "telemost",
		RoomID:    "room-1",
		KeyHex:    "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
		DNSServer: "1.1.1.1:53", //nolint:goconst // test literal, repetition is intentional
	}

	tests := []struct {
		name string
		cfg  Config
		want error
	}{
		{name: "valid baseline", cfg: base},
		{
			name: "jazz allows empty room id",
			cfg: func() Config {
				cfg := base
				cfg.Auth = "jazz"
				cfg.RoomID = ""
				return cfg
			}(),
		},
		{
			name: "cnc requires socks host and port",
			cfg: func() Config {
				cfg := base
				cfg.Mode = modeCNC
				cfg.SOCKSHost = "127.0.0.1"
				cfg.SOCKSPort = 1080
				return cfg
			}(),
		},
		{
			name: "missing mode",
			cfg: func() Config {
				cfg := base
				cfg.Mode = ""
				return cfg
			}(),
			want: ErrModeRequired,
		},
		{
			name: "unsupported carrier",
			cfg: func() Config {
				cfg := base
				cfg.Auth = "unknown" //nolint:goconst // test literal, repetition is intentional
				return cfg
			}(),
			want: ErrUnsupportedCarrier,
		},
		{
			name: "unsupported link",
			cfg: func() Config {
				cfg := base
				cfg.Link = "unknown"
				return cfg
			}(),
			want: ErrUnsupportedLink,
		},
		{
			name: "unsupported transport",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "unknown"
				return cfg
			}(),
			want: ErrUnsupportedTransport,
		},
		{
			name: "room id required for non jazz",
			cfg: func() Config {
				cfg := base
				cfg.RoomID = ""
				return cfg
			}(),
			want: ErrRoomIDRequired,
		},
		{
			name: "key required",
			cfg: func() Config {
				cfg := base
				cfg.KeyHex = ""
				return cfg
			}(),
			want: ErrKeyRequired,
		},
		{
			name: "dns server required",
			cfg: func() Config {
				cfg := base
				cfg.DNSServer = ""
				return cfg
			}(),
			want: ErrDNSServerRequired,
		},
		{
			name: "videochannel requires dimensions and bitrate settings",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel" //nolint:goconst // test literal, repetition is intentional
				return cfg
			}(),
			want: ErrVideoWidthRequired,
		},
		{
			name: "videochannel rejects invalid codec",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				cfg.VideoHeight = 480
				cfg.VideoFPS = 30
				cfg.VideoBitrate = "1M"
				cfg.VideoHW = "none" //nolint:goconst // test literal, repetition is intentional
				cfg.VideoCodec = "bogus"
				return cfg
			}(),
			want: ErrVideoCodecInvalid,
		},
		{
			name: "videochannel requires height",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				return cfg
			}(),
			want: ErrVideoHeightRequired,
		},
		{
			name: "videochannel requires fps",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				cfg.VideoHeight = 480
				return cfg
			}(),
			want: ErrVideoFPSRequired,
		},
		{
			name: "videochannel requires bitrate",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				cfg.VideoHeight = 480
				cfg.VideoFPS = 30
				return cfg
			}(),
			want: ErrVideoBitrateRequired,
		},
		{
			name: "videochannel requires hw",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				cfg.VideoHeight = 480
				cfg.VideoFPS = 30
				cfg.VideoBitrate = "1M"
				return cfg
			}(),
			want: ErrVideoHWRequired,
		},
		{
			name: "tile codec requires square 1080 dimensions",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 640
				cfg.VideoHeight = 480
				cfg.VideoFPS = 30
				cfg.VideoBitrate = "1M"
				cfg.VideoHW = "none"
				cfg.VideoCodec = "tile"
				return cfg
			}(),
			want: ErrTileCodecDimensions,
		},
		{
			name: "videochannel valid",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "videochannel"
				cfg.VideoWidth = 1080
				cfg.VideoHeight = 1080
				cfg.VideoFPS = 30
				cfg.VideoBitrate = "1M"
				cfg.VideoHW = "none"
				cfg.VideoCodec = "tile"
				return cfg
			}(),
		},
		{
			name: "vp8channel requires fps",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "vp8channel" //nolint:goconst // test literal, repetition is intentional
				return cfg
			}(),
			want: ErrVP8FPSRequired,
		},
		{
			name: "vp8channel requires batch size",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "vp8channel"
				cfg.VP8FPS = 25
				return cfg
			}(),
			want: ErrVP8BatchSizeRequired,
		},
		{
			name: "vp8channel valid",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "vp8channel"
				cfg.VP8FPS = 25
				cfg.VP8BatchSize = 16
				return cfg
			}(),
		},
		{
			name: "seichannel requires fps",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "seichannel" //nolint:goconst // test literal, repetition is intentional
				return cfg
			}(),
			want: ErrSEIFPSRequired,
		},
		{
			name: "seichannel requires batch size",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "seichannel"
				cfg.SEIFPS = 20
				return cfg
			}(),
			want: ErrSEIBatchSizeRequired,
		},
		{
			name: "seichannel requires fragment size",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "seichannel"
				cfg.SEIFPS = 20
				cfg.SEIBatchSize = 1
				return cfg
			}(),
			want: ErrSEIFragmentSizeRequired,
		},
		{
			name: "seichannel requires ack timeout",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "seichannel"
				cfg.SEIFPS = 20
				cfg.SEIBatchSize = 1
				cfg.SEIFragmentSize = 900
				return cfg
			}(),
			want: ErrSEIAckTimeoutRequired,
		},
		{
			name: "seichannel valid",
			cfg: func() Config {
				cfg := base
				cfg.Transport = "seichannel"
				cfg.SEIFPS = 20
				cfg.SEIBatchSize = 1
				cfg.SEIFragmentSize = 900
				cfg.SEIAckTimeoutMS = 3000
				return cfg
			}(),
		},
		{
			name: "cnc requires socks host",
			cfg: func() Config {
				cfg := base
				cfg.Mode = modeCNC
				cfg.SOCKSPort = 1080
				return cfg
			}(),
			want: ErrSOCKSHostRequired,
		},
		{
			name: "cnc requires socks port",
			cfg: func() Config {
				cfg := base
				cfg.Mode = modeCNC
				cfg.SOCKSHost = "127.0.0.1"
				return cfg
			}(),
			want: ErrSOCKSPortRequired,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := Validate(tt.cfg)
			if tt.want == nil {
				if err != nil {
					t.Fatalf("Validate() error = %v", err)
				}
				return
			}
			if !errors.Is(err, tt.want) {
				t.Fatalf("Validate() error = %v, want %v", err, tt.want)
			}
		})
	}
}

const testAuthWBStream = "wbstream"

func TestValidateGen(t *testing.T) {
	RegisterDefaults()

	tests := []struct {
		name string
		cfg  Config
		want error
	}{
		{
			name: "valid wbstream",
			cfg:  Config{Auth: testAuthWBStream, DNSServer: "1.1.1.1:53", Amount: 3},
		},
		{
			name: "valid jazz",
			cfg:  Config{Auth: "jazz", DNSServer: "1.1.1.1:53", Amount: 1},
		},
		{
			name: "missing auth",
			cfg:  Config{DNSServer: "1.1.1.1:53", Amount: 1},
			want: ErrAuthRequired,
		},
		{
			name: "unsupported auth",
			cfg:  Config{Auth: "unknown", DNSServer: "1.1.1.1:53", Amount: 1},
			want: ErrUnsupportedCarrier,
		},
		{
			name: "missing dns",
			cfg:  Config{Auth: testAuthWBStream, Amount: 1},
			want: ErrDNSServerRequired,
		},
		{
			name: "amount zero",
			cfg:  Config{Auth: testAuthWBStream, DNSServer: "1.1.1.1:53", Amount: 0},
			want: ErrAmountRequired,
		},
		{
			name: "amount negative",
			cfg:  Config{Auth: testAuthWBStream, DNSServer: "1.1.1.1:53", Amount: -1},
			want: ErrAmountRequired,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateGen(tt.cfg)
			if tt.want == nil {
				if err != nil {
					t.Fatalf("ValidateGen() error = %v", err)
				}
				return
			}
			if !errors.Is(err, tt.want) {
				t.Fatalf("ValidateGen() error = %v, want %v", err, tt.want)
			}
		})
	}
}

func TestGenUnsupportedAuth(t *testing.T) {
	RegisterDefaults()
	cfg := Config{Auth: "telemost", DNSServer: "1.1.1.1:53", Amount: 1}
	err := Gen(context.Background(), cfg, func(string) {})
	if !errors.Is(err, ErrUnsupportedCarrier) {
		t.Fatalf("Gen(telemost) error = %v, want ErrUnsupportedCarrier", err)
	}
}
