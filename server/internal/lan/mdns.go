// Package lan publishes the Kipotify HTTP service using DNS-SD/mDNS.
package lan

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"kipotify/internal/config"

	"github.com/hashicorp/mdns"
)

const (
	serviceType     = "_kipotify._tcp"
	refreshInterval = 2 * time.Second
)

type interfaceAddress struct {
	name  string
	index int
	ip    net.IP
}

type selectedAddresses struct {
	interfaceName string
	ips           []net.IP
}

// Advertisement watches the host's LAN addresses and owns at most one mDNS server.
type Advertisement struct {
	cancel   context.CancelFunc
	done     chan struct{}
	stopOnce sync.Once
}

// StartAdvertisement registers a DNS-SD service for local Android clients and keeps the
// registration synchronized with interface changes. HTTP services are deliberately not
// announced unless explicitly enabled: mDNS records are not authenticated.
func StartAdvertisement(parent context.Context, cfg config.Config) (*Advertisement, error) {
	if !cfg.MDNSEnabled {
		return nil, nil
	}

	usingTLS := cfg.TLSCertFile != "" && cfg.TLSKeyFile != ""
	if !usingTLS && !cfg.MDNSAdvertiseInsecure {
		return nil, nil
	}

	port, err := strconv.Atoi(cfg.Port)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("invalid mDNS service port %q", cfg.Port)
	}

	hostname, err := os.Hostname()
	if err != nil {
		return nil, fmt.Errorf("read hostname: %w", err)
	}
	hostname = strings.TrimSuffix(hostname, ".local") + ".local."
	scheme := "http"
	if usingTLS {
		scheme = "https"
	}

	ctx, cancel := context.WithCancel(parent)
	advertisement := &Advertisement{
		cancel: cancel,
		done:   make(chan struct{}),
	}
	go advertisement.run(ctx, cfg, hostname, scheme, port)
	return advertisement, nil
}

// Shutdown stops address monitoring and the current mDNS server. It is safe to call repeatedly.
func (a *Advertisement) Shutdown() error {
	if a == nil {
		return nil
	}
	a.stopOnce.Do(a.cancel)
	<-a.done
	return nil
}

func (a *Advertisement) run(
	ctx context.Context,
	cfg config.Config,
	hostname string,
	scheme string,
	port int,
) {
	defer close(a.done)

	var server *mdns.Server
	var current selectedAddresses
	initialized := false
	refresh := func() {
		candidates, err := usableInterfaceAddresses(cfg.MDNSInterface)
		if err != nil {
			slog.Warn("mDNS interface scan failed", "error", err)
			return
		}
		preferredIP := defaultRouteIPv4()
		next := selectAddresses(candidates, preferredIP, cfg.MDNSInterface)
		if initialized && !advertisementNeedsRestart(current, next) {
			return
		}

		slog.Info(
			"mDNS LAN addresses changed",
			"old_interface", current.interfaceName,
			"old_addresses", ipStrings(current.ips),
			"new_interface", next.interfaceName,
			"new_addresses", ipStrings(next.ips),
		)
		if server != nil {
			if err := server.Shutdown(); err != nil {
				slog.Warn("mDNS advertiser shutdown failed", "error", err)
			}
			server = nil
		}
		if len(next.ips) == 0 {
			current = next
			initialized = true
			slog.Warn(
				"mDNS advertisement paused; no usable private LAN address",
				"configured_interface", cfg.MDNSInterface,
			)
			return
		}

		replacement, err := newMDNSServer(
			cfg,
			hostname,
			scheme,
			port,
			next.interfaceName,
			next.ips,
		)
		if err != nil {
			slog.Warn(
				"mDNS advertiser restart failed",
				"interface", next.interfaceName,
				"addresses", ipStrings(next.ips),
				"error", err,
			)
			return
		}
		server = replacement
		current = next
		initialized = true
		slog.Info(
			"kipotify backend advertised on local network",
			"service", serviceType,
			"interface", current.interfaceName,
			"addresses", ipStrings(current.ips),
			"port", port,
			"scheme", scheme,
		)
	}

	refresh()
	ticker := time.NewTicker(refreshInterval)
	defer ticker.Stop()
	defer func() {
		if server != nil {
			if err := server.Shutdown(); err != nil {
				slog.Warn("mDNS advertiser shutdown failed", "error", err)
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			refresh()
		}
	}
}

func newMDNSServer(
	cfg config.Config,
	hostname string,
	scheme string,
	port int,
	interfaceName string,
	ips []net.IP,
) (*mdns.Server, error) {
	service, err := mdns.NewMDNSService(
		cfg.MDNSInstance,
		serviceType,
		"local.",
		hostname,
		port,
		ips,
		[]string{
			"kipotify-api=v1",
			"scheme=" + scheme,
			"priority=" + strconv.Itoa(cfg.MDNSPriority),
		},
	)
	if err != nil {
		return nil, fmt.Errorf("build mDNS service: %w", err)
	}
	iface, err := net.InterfaceByName(interfaceName)
	if err != nil {
		return nil, fmt.Errorf("look up mDNS interface %q: %w", interfaceName, err)
	}
	server, err := mdns.NewServer(&mdns.Config{Zone: service, Iface: iface})
	if err != nil {
		return nil, fmt.Errorf("start mDNS service: %w", err)
	}
	return server, nil
}

func usableInterfaceAddresses(explicitInterface string) ([]interfaceAddress, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list interfaces: %w", err)
	}
	var candidates []interfaceAddress
	for _, iface := range interfaces {
		if !usableInterface(iface, explicitInterface) {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			ip, _, err := net.ParseCIDR(address.String())
			if err == nil && usablePrivateIPv4(ip) {
				candidates = append(candidates, interfaceAddress{
					name:  iface.Name,
					index: iface.Index,
					ip:    append(net.IP(nil), ip.To4()...),
				})
			}
		}
	}
	return candidates, nil
}

func usableInterface(iface net.Interface, explicitlyConfigured string) bool {
	if iface.Flags&net.FlagUp == 0 ||
		iface.Flags&net.FlagLoopback != 0 ||
		iface.Flags&net.FlagMulticast == 0 {
		return false
	}
	if explicitlyConfigured != "" {
		return iface.Name == explicitlyConfigured
	}
	return !virtualInterfaceName(iface.Name)
}

func virtualInterfaceName(name string) bool {
	lower := strings.ToLower(name)
	prefixes := []string{
		"docker", "br", "veth", "virbr", "vmnet", "vboxnet",
		"cni", "flannel", "podman", "lxc", "incus", "kube",
		"tun", "tap", "utun", "wg", "tailscale", "zt", "ham",
		"ppp", "ipsec", "vpn", "nordlynx", "proton", "warp", "dummy",
	}
	for _, prefix := range prefixes {
		if strings.HasPrefix(lower, prefix) {
			return true
		}
	}
	return false
}

func usablePrivateIPv4(ip net.IP) bool {
	v4 := ip.To4()
	return v4 != nil &&
		ip.IsPrivate() &&
		!ip.IsLoopback() &&
		!ip.IsLinkLocalUnicast() &&
		!ip.IsUnspecified() &&
		!ip.IsMulticast()
}

func defaultRouteIPv4() net.IP {
	connection, err := net.DialTimeout("udp4", "192.0.2.1:9", time.Second)
	if err != nil {
		return nil
	}
	defer connection.Close()
	address, ok := connection.LocalAddr().(*net.UDPAddr)
	if !ok || address.IP.To4() == nil {
		return nil
	}
	return address.IP.To4()
}

func selectAddresses(
	candidates []interfaceAddress,
	defaultRouteIP net.IP,
	explicitInterface string,
) selectedAddresses {
	filtered := make([]interfaceAddress, 0, len(candidates))
	for _, candidate := range candidates {
		if !usablePrivateIPv4(candidate.ip) {
			continue
		}
		if explicitInterface != "" && candidate.name != explicitInterface {
			continue
		}
		if explicitInterface == "" && virtualInterfaceName(candidate.name) {
			continue
		}
		filtered = append(filtered, candidate)
	}
	sort.Slice(filtered, func(i, j int) bool {
		if filtered[i].name != filtered[j].name {
			return filtered[i].name < filtered[j].name
		}
		if filtered[i].index != filtered[j].index {
			return filtered[i].index < filtered[j].index
		}
		return bytesCompare(filtered[i].ip.To4(), filtered[j].ip.To4()) < 0
	})
	if len(filtered) == 0 {
		return selectedAddresses{}
	}

	selectedInterface := filtered[0].name
	for _, candidate := range filtered {
		if defaultRouteIP != nil && candidate.ip.Equal(defaultRouteIP) {
			selectedInterface = candidate.name
			break
		}
	}
	ips := make([]net.IP, 0, len(filtered))
	for _, candidate := range filtered {
		if candidate.name == selectedInterface {
			ips = append(ips, append(net.IP(nil), candidate.ip.To4()...))
		}
	}
	return selectedAddresses{interfaceName: selectedInterface, ips: ips}
}

func advertisementNeedsRestart(current, next selectedAddresses) bool {
	if current.interfaceName != next.interfaceName || len(current.ips) != len(next.ips) {
		return true
	}
	for index := range current.ips {
		if !current.ips[index].Equal(next.ips[index]) {
			return true
		}
	}
	return false
}

func bytesCompare(left, right net.IP) int {
	for index := range left {
		if left[index] < right[index] {
			return -1
		}
		if left[index] > right[index] {
			return 1
		}
	}
	return 0
}

func ipStrings(ips []net.IP) []string {
	result := make([]string, 0, len(ips))
	for _, ip := range ips {
		result = append(result, ip.String())
	}
	return result
}
