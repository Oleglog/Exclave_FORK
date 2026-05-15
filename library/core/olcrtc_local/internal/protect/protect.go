// Package protect provides functions to protect sockets from VPN routing.
package protect

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"syscall"
	"time"
)

// Protector is called with a socket file descriptor before connect.
// On Android, this calls VpnService.protect(fd) to bypass VPN routing.
var Protector func(fd int) bool //nolint:gochecknoglobals // package-level state intentional

// HTTPDNSServer is the IPv4 DNS resolver used for plain HTTP/HTTPS dials
// from auth providers. The Android client cannot rely on the system
// resolver because, while the VpnService is up, system DNS lookups go
// through the TUN interface and races with the very session the
// HTTP call is trying to set up. Empty string falls back to the system
// resolver (used in tests).
var HTTPDNSServer = "1.1.1.1:53" //nolint:gochecknoglobals // package-level state intentional

// dialTimeout / keepAlive are kept conservative so a stalled hop in the
// auth path surfaces quickly instead of starving the whole startup.
const (
	dialTimeout = 10 * time.Second
	keepAlive   = 30 * time.Second
)

func controlFunc(network, address string, c syscall.RawConn) error {
	if Protector == nil {
		return nil
	}
	var err error
	controlErr := c.Control(func(fd uintptr) {
		if !Protector(int(fd)) {
			log.Printf("[protect] Protector(fd=%d, %s, %s) returned false", int(fd), network, address)
			err = &net.OpError{Op: "protect", Net: network, Err: net.ErrClosed}
		}
	})
	if controlErr != nil {
		return fmt.Errorf("control failed: %w", controlErr)
	}
	return err
}

// NewDialer returns a net.Dialer that calls Protector on each new socket.
//
// The dialer is intentionally IPv4-preferring: most carrier networks the
// Android client runs on (LTE / mobile data) only carry IPv4 reliably, and
// Go's default Happy Eyeballs path otherwise tries an AAAA address first
// and fails with ENETUNREACH on the underlying interface — even after
// VpnService.protect() reroutes the fd. Forcing tcp4 keeps that path
// deterministic. Callers that need IPv6 explicitly should use a custom
// Dialer.
func NewDialer() *net.Dialer {
	return &net.Dialer{
		Timeout:       dialTimeout,
		KeepAlive:     keepAlive,
		Control:       controlFunc,
		FallbackDelay: -1, // disable IPv4/IPv6 Happy Eyeballs race
	}
}

// newProtectedResolver returns a net.Resolver whose own UDP/TCP socket
// to the configured DNS server is protected from VPN routing. This is
// what we plug into NewHTTPClient so that even the resolver lookups
// triggered by the auth providers bypass the TUN interface.
func newProtectedResolver() *net.Resolver {
	if HTTPDNSServer == "" {
		return net.DefaultResolver
	}
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{
				Timeout: dialTimeout,
				Control: controlFunc,
			}
			// The resolver passes "udp" / "tcp" here; route both to our
			// configured upstream so we never hit the system resolver while
			// the VpnService is up.
			return d.DialContext(ctx, network, HTTPDNSServer)
		},
	}
}

// NewHTTPClient returns an http.Client using protected sockets and a
// protected resolver. Outgoing connections are pinned to IPv4 to dodge
// IPv6 routing surprises on mobile carriers.
func NewHTTPClient() *http.Client {
	dialer := NewDialer()
	dialer.Resolver = newProtectedResolver()

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Force tcp4 unless the caller already pinned the family.
			if !strings.HasSuffix(network, "4") && !strings.HasSuffix(network, "6") {
				network = network + "4"
			}
			return dialer.DialContext(ctx, network, addr)
		},
		// HTTP/2 over the auth providers' shared loadbalancers is fine,
		// but disable it for now: pion's default carrier tooling assumes
		// HTTP/1.1 keep-alive semantics for `preconnect`.
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 15 * time.Second,
	}
	return &http.Client{
		Transport: transport,
		Timeout:   25 * time.Second,
	}
}

// DialContext dials using a protected socket.
func DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	conn, err := NewDialer().DialContext(ctx, network, address)
	if err != nil {
		return nil, fmt.Errorf("dial failed: %w", err)
	}
	return conn, nil
}

// ProxyDialer implements golang.org/x/net/proxy.Dialer for pion ICE.
type ProxyDialer struct{}

// Dial connects to the address on the named network using a protected socket.
func (d *ProxyDialer) Dial(network, addr string) (net.Conn, error) {
	conn, err := NewDialer().Dial(network, addr)
	if err != nil {
		return nil, fmt.Errorf("dial failed: %w", err)
	}
	return conn, nil
}

// NewProxyDialer returns a proxy.Dialer that protects ICE sockets.
func NewProxyDialer() *ProxyDialer {
	return &ProxyDialer{}
}
