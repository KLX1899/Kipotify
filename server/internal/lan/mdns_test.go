package lan

import (
	"net"
	"testing"
)

func TestUsableInterfaceAndAddressFiltering(t *testing.T) {
	t.Parallel()

	upMulticast := net.FlagUp | net.FlagMulticast
	tests := []struct {
		name     string
		iface    net.Interface
		explicit string
		want     bool
	}{
		{"physical", net.Interface{Name: "wlp2s0", Flags: upMulticast}, "", true},
		{"down", net.Interface{Name: "wlp2s0", Flags: net.FlagMulticast}, "", false},
		{"loopback", net.Interface{Name: "lo", Flags: upMulticast | net.FlagLoopback}, "", false},
		{"no multicast", net.Interface{Name: "enp3s0", Flags: net.FlagUp}, "", false},
		{"docker", net.Interface{Name: "docker0", Flags: upMulticast}, "", false},
		{"bridge", net.Interface{Name: "br-abcd", Flags: upMulticast}, "", false},
		{"bridge zero", net.Interface{Name: "br0", Flags: upMulticast}, "", false},
		{"explicit virtual", net.Interface{Name: "br-abcd", Flags: upMulticast}, "br-abcd", true},
		{"different explicit", net.Interface{Name: "wlp2s0", Flags: upMulticast}, "enp3s0", false},
	}
	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if got := usableInterface(test.iface, test.explicit); got != test.want {
				t.Fatalf("usableInterface() = %v, want %v", got, test.want)
			}
		})
	}

	for _, address := range []string{"10.20.30.4", "172.16.2.3", "192.168.1.8"} {
		if !usablePrivateIPv4(net.ParseIP(address)) {
			t.Errorf("%s should be usable", address)
		}
	}
	for _, address := range []string{"127.0.0.1", "169.254.1.2", "8.8.8.8", "::1", "224.0.0.1"} {
		if usablePrivateIPv4(net.ParseIP(address)) {
			t.Errorf("%s should be rejected", address)
		}
	}
}

func TestSelectAddressesPrefersDefaultRouteDeterministically(t *testing.T) {
	t.Parallel()

	input := []interfaceAddress{
		{name: "wlp2s0", index: 3, ip: net.ParseIP("192.168.50.20")},
		{name: "enp3s0", index: 2, ip: net.ParseIP("10.0.0.9")},
		{name: "wlp2s0", index: 3, ip: net.ParseIP("192.168.50.10")},
		{name: "docker0", index: 4, ip: net.ParseIP("172.17.0.1")},
	}
	selected := selectAddresses(input, net.ParseIP("192.168.50.20"), "")
	if selected.interfaceName != "wlp2s0" {
		t.Fatalf("selected interface = %q, want wlp2s0", selected.interfaceName)
	}
	got := ipStrings(selected.ips)
	want := []string{"192.168.50.10", "192.168.50.20"}
	if len(got) != len(want) {
		t.Fatalf("selected addresses = %v, want %v", got, want)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("selected addresses = %v, want %v", got, want)
		}
	}

	selected = selectAddresses(input, nil, "enp3s0")
	if selected.interfaceName != "enp3s0" || len(selected.ips) != 1 ||
		selected.ips[0].String() != "10.0.0.9" {
		t.Fatalf("explicit selection = %+v", selected)
	}
}

func TestAdvertisementNeedsRestart(t *testing.T) {
	t.Parallel()

	wifi := selectedAddresses{
		interfaceName: "wlp2s0",
		ips:           []net.IP{net.ParseIP("192.168.1.20")},
	}
	same := selectedAddresses{
		interfaceName: "wlp2s0",
		ips:           []net.IP{net.ParseIP("192.168.1.20")},
	}
	if advertisementNeedsRestart(wifi, same) {
		t.Fatal("equal selections must not restart")
	}
	if !advertisementNeedsRestart(wifi, selectedAddresses{
		interfaceName: "enp3s0",
		ips:           []net.IP{net.ParseIP("192.168.1.20")},
	}) {
		t.Fatal("interface change must restart")
	}
	if !advertisementNeedsRestart(wifi, selectedAddresses{
		interfaceName: "wlp2s0",
		ips:           []net.IP{net.ParseIP("192.168.1.21")},
	}) {
		t.Fatal("address change must restart")
	}
	if !advertisementNeedsRestart(wifi, selectedAddresses{}) {
		t.Fatal("loss of all addresses must stop the advertiser")
	}
}
