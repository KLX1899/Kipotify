// Package lan publishes the Kipotify HTTP service using DNS-SD/mDNS.
package lan

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

	"kipotify/internal/config"

	"github.com/hashicorp/mdns"
)

const serviceType = "_kipotify._tcp"

// StartAdvertisement registers a DNS-SD service for local Android clients. HTTP services are
// deliberately not announced unless explicitly enabled: mDNS records are not authenticated.
func StartAdvertisement(cfg config.Config) (*mdns.Server, error) {
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
	ips, err := privateIPv4Addresses()
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no private IPv4 address available for mDNS advertisement")
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
	server, err := mdns.NewServer(&mdns.Config{Zone: service})
	if err != nil {
		return nil, fmt.Errorf("start mDNS service: %w", err)
	}
	return server, nil
}

func privateIPv4Addresses() ([]net.IP, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list interfaces: %w", err)
	}
	var ips []net.IP
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagMulticast == 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			ip, _, err := net.ParseCIDR(address.String())
			if err == nil && ip.To4() != nil && ip.IsPrivate() {
				ips = append(ips, ip)
			}
		}
	}
	return ips, nil
}
