package vkturnmobile

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"

	vkturnclient "github.com/cacggghp/vk-turn-proxy/client"
)

type LogWriter interface {
	WriteLog(msg string)
}

type CaptchaListener interface {
	ShowCaptcha(url string)
}

var (
	mu              sync.Mutex
	cancel          context.CancelFunc
	done            chan struct{}
	ready           chan struct{}
	lastErr         error
	logWriter       LogWriter
	captchaListener CaptchaListener
)

type mobileLogWriter struct{}

func (mobileLogWriter) Write(p []byte) (int, error) {
	mu.Lock()
	w := logWriter
	mu.Unlock()
	if w != nil {
		w.WriteLog(string(p))
	}
	return len(p), nil
}

func init() {
	log.SetFlags(0)
	log.SetOutput(mobileLogWriter{})
}

func SetLogWriter(writer LogWriter) {
	mu.Lock()
	logWriter = writer
	mu.Unlock()
}

func SetCaptchaListener(listener CaptchaListener) {
	mu.Lock()
	captchaListener = listener
	mu.Unlock()
}

func Start(
	peerAddr string,
	vkLink string,
	listenAddr string,
	vless bool,
	vlessBond bool,
	streams int64,
	streamsPerCred int64,
	udp bool,
	manualCaptcha bool,
	turnHost string,
	turnPort string,
	wrap bool,
	wrapKeyHex string,
	debug bool,
	dnsMode string,
	dnsServers string,
) {
	mu.Lock()
	if cancel != nil {
		mu.Unlock()
		panic("vk-turn client is already running")
	}
	ctx, c := context.WithCancel(context.Background())
	cancel = c
	done = make(chan struct{})
	ready = make(chan struct{})
	d := done
	r := ready
	mu.Unlock()

	vkturnclient.SetCaptchaURLHandler(func(url string) {
		mu.Lock()
		listener := captchaListener
		mu.Unlock()
		if listener != nil {
			listener.ShowCaptcha(url)
		}
	})

	opts := vkturnclient.Options{
		TurnHost:       turnHost,
		TurnPort:       turnPort,
		Listen:         listenAddr,
		VKLink:         vkLink,
		PeerAddr:       peerAddr,
		Streams:        int(streams),
		StreamsPerCred: int(streamsPerCred),
		UDP:            udp,
		ManualCaptcha:  manualCaptcha,
		VLESS:          vless,
		VLESSBond:      vlessBond,
		Wrap:           wrap,
		WrapKeyHex:     wrapKeyHex,
		Debug:          debug,
		DNSMode:        dnsMode,
		DNSServers:     dnsServers,
		Ready:          r,
	}
	go func() {
		err := vkturnclient.Start(ctx, opts)
		mu.Lock()
		lastErr = err
		mu.Unlock()
		close(d)
	}()
}

func WaitReady(timeoutMillis int64) {
	mu.Lock()
	r := ready
	d := done
	mu.Unlock()
	if r == nil {
		panic("vk-turn client is not running")
	}
	timer := time.NewTimer(time.Duration(timeoutMillis) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-r:
		return
	case <-d:
		mu.Lock()
		err := lastErr
		mu.Unlock()
		if err == nil || errors.Is(err, context.Canceled) {
			return
		}
		mu.Lock()
		cancel = nil
		done = nil
		ready = nil
		mu.Unlock()
		panic(err.Error())
	case <-timer.C:
		panic("vk-turn client startup timed out")
	}
}

func Stop() {
	mu.Lock()
	c := cancel
	d := done
	cancel = nil
	done = nil
	ready = nil
	lastErr = nil
	vkturnclient.SetCaptchaURLHandler(nil)
	mu.Unlock()
	if c != nil {
		c()
	}
	if d != nil {
		<-d
	}
}

func LastError() string {
	mu.Lock()
	d := done
	mu.Unlock()
	if d == nil {
		return ""
	}
	select {
	case <-d:
		mu.Lock()
		err := lastErr
		mu.Unlock()
		if err == nil || errors.Is(err, context.Canceled) {
			return ""
		}
		return err.Error()
	default:
		return ""
	}
}
