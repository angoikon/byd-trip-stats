// Copied into cmd/tailscaled/ before building the bundled daemon.
// See THIRD_PARTY_NOTICES.md for the full build recipe.

package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Android leaves Go's resolver with nothing to work with.
//
// There is no /etc/resolv.conf on Android — /etc is a symlink to /system/etc, which is mounted
// read-only, so nothing can create one either. This binary is built CGO_ENABLED=0, so there is no
// libc fallback to the platform resolver. Go's pure-Go resolver therefore finds no nameservers and
// falls back to its built-in defaults of 127.0.0.1:53 and [::1]:53, where nothing is listening:
//
//	lookup acme-v02.api.letsencrypt.org on [::1]:53: read: connection refused
//
// The tailnet itself is unaffected, which is why this stays hidden for so long: the control plane
// bootstraps from hardcoded addresses rather than DNS. Only ordinary hostname lookups fail — and
// the one that matters is fetching a Let's Encrypt certificate for `tailscale serve`, whose ACME
// client is built with no custom HTTP client (feature/acme/certstore.go) and so uses Go's default
// transport and default resolver.
func init() {
	useAndroidCACerts()
	useAndroidResolver()
}

// Android's CA store, as Go's own crypto/x509 names it when built for GOOS=android.
const (
	androidCACerts     = "/system/etc/security/cacerts"
	androidUserCACerts = "/data/misc/keychain/certs-added"
)

// useAndroidCACerts points TLS verification at Android's root certificates.
//
// crypto/x509 knows about them, but only behind `if goos.IsAndroid == 1` — and this binary is
// built GOOS=linux, so that branch is compiled out. It then searches /etc/ssl/certs and friends,
// which do not exist here, ends up with an empty root pool, and fails every verification with
// "x509: certificate signed by unknown authority". That is where fetching the Let's Encrypt
// certificate died once DNS was working.
//
// SSL_CERT_DIR is crypto/x509's own documented override and replaces the search list outright, so
// no patching of the standard library is needed — just pointing it at the store that is there.
func useAndroidCACerts() {
	if os.Getenv("SSL_CERT_DIR") != "" {
		return // deliberately configured elsewhere; leave it alone
	}
	if _, err := os.Stat(androidCACerts); err != nil {
		return // not Android, or no store to point at
	}
	// Both directories, matching what crypto/x509 does natively on Android: the system roots plus
	// any CA the owner has chosen to trust.
	os.Setenv("SSL_CERT_DIR", androidCACerts+":"+androidUserCACerts)
}

func useAndroidResolver() {
	// A normal Linux box has resolv.conf and needs none of this; leaving its resolver alone keeps
	// this file a no-op anywhere but Android.
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = func(ctx context.Context, network, _ string) (net.Conn, error) {
		// The address Go passes is one of its own unusable defaults, so it is ignored.
		server := workingServer()
		return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, server)
	}
}

// probeName is what candidate servers are tested with: the lookup this whole file exists to make
// work. Testing the real thing beats testing something easier.
const probeName = "acme-v02.api.letsencrypt.org"

var (
	chosenMu sync.Mutex
	chosen   string
	chosenAt time.Time
)

// workingServer returns a nameserver that actually answers, remembering it for a few minutes.
//
// Picking by configuration alone is not enough, and the reason is worth recording: the head unit
// publishes its *cellular* nameservers in system properties, but when it is on Wi-Fi the route to
// them does not exist, so queries leave the Wi-Fi address bound for a carrier-internal resolver and
// time out. Nor can that be handled by trying each server in turn inside the dial: a UDP "dial"
// succeeds for any address whatsoever, so the first candidate always wins and the failure only
// surfaces later, as a read timeout inside Go's own resolver where this code cannot see it.
//
// So each candidate is probed with a real query before being used.
func workingServer() string {
	chosenMu.Lock()
	defer chosenMu.Unlock()

	if chosen != "" && time.Since(chosenAt) < 5*time.Minute {
		return chosen
	}

	candidates := candidateServers()
	for _, server := range candidates {
		if answers(server) {
			chosen, chosenAt = server, time.Now()
			return server
		}
	}
	// Nothing answered. Hand back the last candidate so the caller gets a real error from a real
	// attempt rather than silence, and don't cache the choice.
	chosen, chosenAt = "", time.Time{}
	return candidates[len(candidates)-1]
}

// answers reports whether server resolves [probeName] within a short budget. It uses its own
// Resolver, never the default one, so this can be called from inside the default resolver's dial
// without recursing.
func answers(server string) bool {
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return (&net.Dialer{Timeout: 2 * time.Second}).DialContext(ctx, network, server)
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	addrs, err := resolver.LookupHost(ctx, probeName)
	return err == nil && len(addrs) > 0
}

// candidateServers lists nameservers to try, most appropriate first: the gateway of the active
// default route (a home router almost always resolves, and keeps queries local), then whatever
// Android publishes as system properties, then public resolvers as a last resort.
//
// Order is only a preference — [workingServer] probes before trusting any of them.
func candidateServers() []string {
	var out []string
	seen := map[string]bool{}
	add := func(ip string) {
		if ip == "" || seen[ip] {
			return
		}
		seen[ip] = true
		out = append(out, net.JoinHostPort(ip, "53"))
	}

	add(defaultGateway())
	for _, ip := range gatewayGuesses() {
		add(ip)
	}
	for _, ip := range androidNameservers() {
		add(ip)
	}
	// Reached only when nothing local answers. The single query this leaks is the ACME directory
	// hostname, which says the car uses Let's Encrypt and nothing else.
	add("1.1.1.1")
	add("8.8.8.8")
	return out
}

// defaultGateway reads the gateway of the default route from /proc/net/route, which Android keeps
// even where it publishes no DNS properties for the interface (Wi-Fi, typically).
func defaultGateway() string {
	file, err := os.Open("/proc/net/route")
	if err != nil {
		return ""
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	scanner.Scan() // header
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		// Iface Destination Gateway Flags RefCnt Use Metric Mask ...
		if len(fields) < 3 || fields[1] != "00000000" {
			continue
		}
		raw, err := hex.DecodeString(fields[2])
		if err != nil || len(raw) != 4 {
			continue
		}
		// The kernel prints the address as a little-endian hex word.
		ip := make(net.IP, 4)
		binary.LittleEndian.PutUint32(ip, binary.BigEndian.Uint32(raw))
		if ip.Equal(net.IPv4zero) {
			continue
		}
		return ip.String()
	}
	return ""
}

// gatewayGuesses returns the first host address of every attached IPv4 subnet.
//
// Android keeps default routes in per-network policy tables rather than the main one, so
// [defaultGateway] finds nothing on a head unit — its /proc/net/route holds only subnet routes,
// with no 00000000 destination at all. The first host of a subnet is where a home router's
// resolver almost always sits, and keeping the query on the local network beats sending it to a
// public resolver. A wrong guess costs a single probe.
func gatewayGuesses() []string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			prefix, ok := addr.(*net.IPNet)
			if !ok || prefix.IP.To4() == nil {
				continue
			}
			network := prefix.IP.Mask(prefix.Mask).To4()
			if network == nil {
				continue
			}
			gateway := net.IP{network[0], network[1], network[2], network[3] + 1}
			if gateway.Equal(prefix.IP.To4()) {
				continue // that address is ours
			}
			out = append(out, gateway.String())
		}
	}
	return out
}

// androidNameservers reads the DNS servers Android publishes as system properties, e.g.
//
//	[net.rmnet_data0.dns1]: [62.74.130.15]
//
// Only "net.*" keys holding a parseable IP are taken, which drops unrelated entries such as
// sys.dns.vehicleCode on BYD head units.
func androidNameservers() []string {
	out, err := exec.Command("/system/bin/getprop").Output()
	if err != nil {
		if out, err = exec.Command("getprop").Output(); err != nil {
			return nil
		}
	}

	var servers []string
	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := scanner.Text()
		sep := strings.Index(line, "]: [")
		if sep < 0 || !strings.HasPrefix(line, "[net.") {
			continue
		}
		if !strings.Contains(line[1:sep], ".dns") {
			continue
		}
		value := strings.TrimSuffix(line[sep+len("]: ["):], "]")
		if net.ParseIP(value) == nil {
			continue
		}
		servers = append(servers, value)
	}
	return servers
}
