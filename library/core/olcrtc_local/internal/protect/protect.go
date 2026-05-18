// Package protect provides functions to protect sockets from VPN routing.
package protect

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
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
var HTTPDNSServer = "77.88.8.8:53" //nolint:gochecknoglobals // package-level state intentional

// DNSFallbackServers is the static list of well-known public resolvers
// raced in parallel with the user-configured server. Carriers that
// blackhole one of them (a common situation on Russian mobile data)
// will still succeed via a sibling endpoint. All servers are tried
// over both UDP/53 and TCP/53 simultaneously.
var DNSFallbackServers = []string{ //nolint:gochecknoglobals // package-level state intentional
	"1.1.1.1:53",
	"1.0.0.1:53",
	"8.8.8.8:53",
	"8.8.4.4:53",
	"77.88.8.8:53",
	"77.88.8.1:53",
	"9.9.9.9:53",
}

// dialTimeout / keepAlive / perServerDNSTimeout are kept conservative so
// a stalled hop in the auth path surfaces quickly instead of starving
// the whole startup.
const (
	dialTimeout         = 10 * time.Second
	keepAlive           = 30 * time.Second
	perServerDNSTimeout = 3 * time.Second
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

// raceResolve resolves host to a list of IPv4 addresses by querying the
// configured HTTPDNSServer and every entry of DNSFallbackServers over
// both UDP/53 and TCP/53 in parallel. The first goroutine to return a
// non-empty list of A records wins; everyone else is cancelled. All
// sockets go through controlFunc so they bypass the Android VPN tunnel.
//
// Returns an aggregated error if every (server, protocol) pair fails
// within ctx.
//
//nolint:cyclop // the parallel race naturally has several terminal branches
func raceResolve(ctx context.Context, host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			return []net.IP{v4}, nil
		}
	}

	servers := dnsServerList()
	if len(servers) == 0 {
		return nil, errors.New("no DNS servers configured")
	}

	raceCtx, cancel := context.WithTimeout(ctx, dialTimeout)
	defer cancel()

	type raceResult struct {
		ips []net.IP
		err error
		src string
	}

	networks := []string{"udp4", "tcp4"}
	total := len(servers) * len(networks)
	resCh := make(chan raceResult, total)
	var wg sync.WaitGroup

	for _, srv := range servers {
		for _, network := range networks {
			wg.Add(1)
			go func(server, network string) {
				defer wg.Done()
				ips, err := lookupVia(raceCtx, host, server, network)
				select {
				case resCh <- raceResult{ips: ips, err: err, src: network + "://" + server}:
				case <-raceCtx.Done():
				}
			}(srv, network)
		}
	}

	go func() {
		wg.Wait()
		close(resCh)
	}()

	var lastErr error
	for res := range resCh {
		if res.err == nil && len(res.ips) > 0 {
			log.Printf("[protect] DNS race won: %s for %s (%d IPv4)", res.src, host, len(res.ips))
			return res.ips, nil
		}
		if res.err != nil {
			lastErr = res.err
		}
	}

	if lastErr == nil {
		lastErr = fmt.Errorf("all %d DNS race attempts returned no IPv4 for %s", total, host)
	}
	return nil, fmt.Errorf("DNS race failed for %s: %w", host, lastErr)
}

// dnsServerList returns the configured upstream first (if any), followed
// by the static fallbacks with the configured one de-duplicated.
func dnsServerList() []string {
	seen := map[string]bool{}
	out := make([]string, 0, 1+len(DNSFallbackServers))
	if HTTPDNSServer != "" {
		out = append(out, HTTPDNSServer)
		seen[HTTPDNSServer] = true
	}
	for _, s := range DNSFallbackServers {
		if !seen[s] {
			out = append(out, s)
			seen[s] = true
		}
	}
	return out
}

// lookupVia performs a single A-record lookup for host against the given
// server (host:port) using the given protected transport (udp4 or tcp4).
// It returns only IPv4 addresses.
func lookupVia(ctx context.Context, host, server, network string) ([]net.IP, error) {
	r := &net.Resolver{
		PreferGo: true,
		Dial: func(c context.Context, _, _ string) (net.Conn, error) {
			d := net.Dialer{
				Timeout: perServerDNSTimeout,
				Control: controlFunc,
			}
			return d.DialContext(c, network, server)
		},
	}

	queryCtx, cancel := context.WithTimeout(ctx, perServerDNSTimeout)
	defer cancel()

	addrs, err := r.LookupIPAddr(queryCtx, host)
	if err != nil {
		return nil, fmt.Errorf("%s://%s: %w", network, server, err)
	}

	out := make([]net.IP, 0, len(addrs))
	for _, a := range addrs {
		if v4 := a.IP.To4(); v4 != nil {
			out = append(out, v4)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("%s://%s: no IPv4 for %s", network, server, host)
	}
	return out, nil
}

// NewHTTPClient returns an http.Client using protected sockets and a
// protected resolver. Outgoing connections are pinned to IPv4 to dodge
// IPv6 routing surprises on mobile carriers.
//
// Hostname resolution is performed via raceResolve, which queries all
// configured upstreams (HTTPDNSServer + DNSFallbackServers) over both
// UDP/53 and TCP/53 in parallel and takes the first success. The
// connect step then dials each returned IPv4 in order until one
// succeeds, again through a protected socket.
func NewHTTPClient() *http.Client {
	transport := &http.Transport{
		DialContext:           protectedRaceDial,
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

// protectedRaceDial resolves the host part of addr via raceResolve and
// dials each returned IPv4 in order using a protected tcp4 socket. If
// addr is already a literal IP, it is dialed directly (still protected).
func protectedRaceDial(ctx context.Context, network, addr string) (net.Conn, error) {
	if !strings.HasSuffix(network, "4") && !strings.HasSuffix(network, "6") {
		network = network + "4"
	}

	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, fmt.Errorf("split host/port %q: %w", addr, err)
	}

	dialer := NewDialer()

	if ip := net.ParseIP(host); ip != nil {
		_ = ip
		return dialer.DialContext(ctx, network, addr)
	}

	ips, err := raceResolve(ctx, host)
	if err != nil {
		return nil, err
	}

	var lastErr error
	for _, ip := range ips {
		conn, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		log.Printf("[protect] dial %s://%s failed: %v", network, net.JoinHostPort(ip.String(), port), dialErr)
		lastErr = dialErr
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no IPv4 returned for %s", host)
	}
	return nil, fmt.Errorf("dial %s: %w", host, lastErr)
}

// DialContext dials using a protected socket with race-based DNS
// resolution. Forces tcp4 for plain "tcp" networks (same as NewHTTPClient)
// to avoid IPv6 routing issues on mobile carriers.
func DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if !strings.HasSuffix(network, "4") && !strings.HasSuffix(network, "6") {
		network = network + "4"
	}
	conn, err := protectedRaceDial(ctx, network, address)
	if err != nil {
		return nil, fmt.Errorf("dial failed: %w", err)
	}
	return conn, nil
}

// ProxyDialer implements golang.org/x/net/proxy.Dialer for pion ICE.
type ProxyDialer struct{}

// Dial connects to the address on the named network using a protected
// socket and race-based DNS resolution. Forces tcp4 for plain "tcp" to
// match DialContext behaviour.
func (d *ProxyDialer) Dial(network, addr string) (net.Conn, error) {
	if !strings.HasSuffix(network, "4") && !strings.HasSuffix(network, "6") {
		network = network + "4"
	}
	conn, err := protectedRaceDial(context.Background(), network, addr)
	if err != nil {
		return nil, fmt.Errorf("dial failed: %w", err)
	}
	return conn, nil
}

// NewProxyDialer returns a proxy.Dialer that protects ICE sockets.
func NewProxyDialer() *ProxyDialer {
	return &ProxyDialer{}
}
