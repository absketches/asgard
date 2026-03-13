# Network Setup

Asgard is a Linux proxy server (tested with Raspberry Pi 02W) and monitors outbound traffic from any device you point at it. This document covers how to connect a device to Asgard and what to expect.

---

## How it works

Asgard runs as an HTTP/HTTPS proxy on your Pi. You configure your device to route its traffic through Asgard — Asgard inspects, classifies, and forwards every request transparently. Nothing is blocked unless you add an explicit rule. The device behaves exactly as normal; you just gain visibility into what it is actually sending out.

```
Your Device
    │  proxy settings point to (ip:8888)
    ▼
Pi running Asgard
    │  classifies and forwards
    ▼
Internet
```

---

## Connecting your device

### macOS

macOS has two separate proxy mechanisms that do not affect each other:

- **`networksetup`** — sets the proxy for GUI apps (Safari, Chrome, Electron) via the macOS Network framework
- **Environment variables** — the only way to route terminal tools (`curl`, `wget`, scripts)

#### GUI apps (Safari, Chrome, Electron)

Run these two commands, replacing `ASGARD_IP` with your Pi's local IP address:

```bash
sudo networksetup -setwebproxy Wi-Fi ASGARD_IP 8888
sudo networksetup -setsecurewebproxy Wi-Fi ASGARD_IP 8888
```

To stop monitoring:

```bash
sudo networksetup -setwebproxystate Wi-Fi off
sudo networksetup -setsecurewebproxystate Wi-Fi off
```

You can verify the setting is saved in **System Settings → Network → Wi-Fi → Details → Proxies**.

#### Terminal tools (curl, wget, scripts)

`networksetup` has no effect on terminal tools. Set the proxy via environment variables instead:

```bash
export http_proxy=http://ASGARD_IP:8888
export https_proxy=http://ASGARD_IP:8888
```

Add these to your `~/.zshrc` to make them permanent. Once set, `curl`, `wget`, and most CLI tools route through Asgard automatically without any extra flags.

To verify it is working:

```bash
curl -v http://example.com 2>&1 | grep "Using proxy"
# Should show: *   Trying ASGARD_IP:8888...
```

To stop routing terminal traffic through Asgard:

```bash
unset http_proxy
unset https_proxy
```

### Linux

For a persistent system-wide setting, go to **System Settings → Network → WiFi → Proxy** and set:

- Method: Manual
- HTTP Proxy: `ASGARD_IP` Port `8888`
- HTTPS Proxy: `ASGARD_IP` Port `8888`

For a terminal session:

```bash
export http_proxy=http://ASGARD_IP:8888
export https_proxy=http://ASGARD_IP:8888
```

### Firefox (any OS, browser traffic only)

By default Firefox ignores system proxy settings and manages its own. To route Firefox through Asgard:

**Settings → General → Network Settings → Use system proxy settings**

This makes Firefox honour whatever proxy you have set at the OS level. Alternatively, select **Manual proxy configuration** and enter:

- HTTP Proxy: `ASGARD_IP` Port `8888`
- Check: Also use this proxy for HTTPS

### curl and CLI tools

Set the `http_proxy` and `https_proxy` environment variables as described in the [macOS](#macos) or [Linux](#linux) sections above. Once set, `curl`, `wget`, Python, Node.js, and most CLI tools route through Asgard automatically.

## Finding your Pi's IP

If you do not know your Pi's local IP address, SSH in and run:

```bash
ip addr show wlan0
# Look for: inet 192.168.x.x
```

> **Tip:** Your Pi's local IP can change if it reconnects to the network. If Asgard stops receiving traffic, check the IP has not changed and update your proxy settings if needed. This can be avoided by setting a DHCP reservation in your router admin panel — most routers support this under a setting called Address Reservation or Static Lease.

---

## Dashboard

Once your device is connected, open:

```
http://your-pi.local:8080/asgard
```

The dashboard shows a live feed of every request classified in real time.

---

## What Asgard can see

| Traffic | Visible to Asgard |
|---|---|
| Destination hostname | ✅ Always |
| Request method (GET, POST…) | ✅ HTTP only |
| Data volume | ✅ HTTP only |
| Request frequency / timing | ✅ Always |
| URL path and query string | ✅ HTTP only |
| Request body | ❌ HTTPS encrypted |

For HTTPS traffic, the payload is encrypted end-to-end. Asgard sees the hostname, frequency, and timing — which is sufficient to detect the most common exfiltration patterns. A script silently uploading data to an unknown host is detectable by destination and volume alone, without inspecting the payload.

---

## Traffic classifications

| Label | What it means |
|---|---|
| `NORMAL` | Known services — Apple, Google, Microsoft, common CDNs |
| `TRACKING` | Ad networks, analytics, fingerprinting endpoints |
| `SUSPICIOUS` | Unknown destination, unusual frequency, beaconing patterns |
| `EXFILTRATION_RISK` | Large upload to unknown host, repeated encoded payloads |

Classifications are powered by the [oisd.nl](https://oisd.nl) domain blocklist, updated daily, combined with Asgard's own heuristic engine.

---

## Block rules

Block rules can be added directly from the dashboard. Blocked hosts receive a `403 Forbidden` response — the connection is dropped before it reaches the internet.

---

## Coverage limitations

Asgard works by sitting at the system proxy layer. Most browsers and well-behaved apps respect this automatically — Safari, Chrome, Firefox (with system proxy enabled), and most Electron apps will route through Asgard without any extra configuration.

However, some tools bypass system proxy settings entirely and connect directly to the internet:

| Tool | Monitored by default |
|---|---|
| Safari, Chrome | ✅ Yes (macOS system proxy) |
| Firefox | ✅ Yes (when set to use system proxy) |
| Most Electron apps | ✅ Yes |
| `curl`, `wget` | ✅ Yes (when `http_proxy` env vars are set) |
| Node.js / Python scripts | ✅ Yes (when `http_proxy` env vars are set) |
| Apps with custom network stacks | ❌ No |

> **Future:** Full coverage of all traffic including apps that bypass system proxy settings entirely requires routing at the network level rather than the application level. I hope a future version of Asgard will support this via a local VPN tunnel on the monitored device, capturing all outbound traffic regardless of whether the app respects proxy settings.

---

## Privacy

All data stays on your Pi. Asgard fetches the daily oisd.nl blocklist update. The local request log has a configurable cap — oldest entries are dropped automatically when it is reached.
