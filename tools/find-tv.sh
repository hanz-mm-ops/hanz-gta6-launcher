#!/bin/zsh
# Find Android TVs on this network: ping-sweep the local /24, then list hosts with ADB open.
# Usage: tools/find-tv.sh
set -e
iface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
me=$(ipconfig getifaddr "${iface:-en0}" 2>/dev/null || hostname -I | awk '{print $1}')
net=${me%.*}
echo "Scanning $net.0/24 from $me ..."
for i in {1..254}; do ping -c1 -W 400 $net.$i >/dev/null 2>&1 & done; wait
for ip in $(arp -a | grep -v incomplete | grep -oE "$net\.[0-9]+" | sort -u -t. -k4 -n); do
  if nc -z -G1 $ip 5555 2>/dev/null; then echo "ADB open:   $ip"; else echo "host:       $ip"; fi
done
echo "Advertised ADB services:"; adb mdns services 2>/dev/null | tail -n +2
