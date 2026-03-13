# Asgard

A lightweight HTTP/HTTPS proxy that runs on a Raspberry Pi and gives you real-time visibility into everything your devices are sending out.

Point your Mac, phone, or any device at Asgard. Every outbound request is classified, logged, and surfaced on a live dashboard. No cloud. No agents. Nothing installed on the monitored device.

---

## Features

### Live traffic dashboard

Every request that passes through the proxy appears on the dashboard in real time. Requests are colour-coded by classification and grouped into tabs so you can focus on what matters.

```
http://<pi-ip>:8080/asgard
```

### Traffic classification

Each request is automatically classified into one of four categories:

| Label | What it means |
|---|---|
| `NORMAL` | Known services — Apple, Google, Microsoft, common CDNs |
| `TRACKING` | Ad networks, analytics, and fingerprinting endpoints |
| `SUSPICIOUS` | Unknown destination, unusual frequency, or beaconing patterns |
| `EXFILTRATION_RISK` | Large upload to an unknown host, or repeated encoded payloads |

Classification is powered by the [oisd.nl](https://oisd.nl) blocklist, refreshed daily, combined with Asgard's own heuristic engine that detects beaconing and unusual upload patterns without inspecting encrypted payloads.

### Blocking

Any host can be blocked directly from the dashboard. Blocked hosts receive a `403 Forbidden` response — the connection is dropped before it reaches the internet. Blocks are persisted across restarts.

Blocked hosts appear under the **⊘ BLOCKED** tab and are marked inline in the traffic table.

### Clearing records

Each tab has a **⌫ CLEAR** button that removes all records for that classification from both the dashboard and the database. To wipe everything, switch to the **ALL** tab and clear from there.

### oisd.nl blocklist

Asgard ships with daily automatic updates from the [oisd.nl](https://oisd.nl) domain blocklist — one of the most comprehensive and false-positive-free domain blocklists available. It covers ad networks, trackers, malware, and phishing domains. The update runs in the background without interrupting the proxy.

### Persistent storage

All traffic is stored in a local SQLite database at `~/.asgard/asgard.db`. A configurable row cap (default 50,000) ensures the database never grows unbounded — oldest records are dropped automatically when the cap is reached. User block rules are stored separately and are never affected by the cap or clear operations.

---

## Limitations

### HTTPS payload is opaque

For HTTPS traffic, Asgard establishes a TCP tunnel (`CONNECT`) and forwards the encrypted stream without interception. This means:

- **Hostname** — always visible
- **Request method and path** — not visible
- **Request or response body** — not visible
- **Transfer size** — not visible (shows `—` in the dashboard)

Detection of HTTPS exfiltration relies on destination hostname, request frequency, and upload volume heuristics rather than payload inspection. A script silently beaconing to an unknown host every 30 seconds is detectable. The content of what it sends is not.

### Proxy-aware apps only

Asgard works at the system proxy layer. Apps that respect OS proxy settings — browsers, most Electron apps, curl with env vars set — are monitored automatically. Apps with hardcoded network stacks or custom DNS resolvers bypass the proxy entirely and are invisible to Asgard.

### Single-device proxy

Asgard monitors one device at a time unless multiple devices are configured to point at the same Pi. Each device must have its proxy settings configured manually — Asgard does not intercept traffic at the router or network level.

### No DNS monitoring

Asgard sees only the traffic routed through it. DNS queries are not monitored. A domain can be resolved and contacted directly without passing through the proxy if the app does not honour proxy settings.

### Classification is heuristic

The `SUSPICIOUS` and `EXFILTRATION_RISK` labels are based on heuristics — unknown domain, high request frequency, large upload volume. They are signals, not verdicts. Legitimate apps can trigger them (a router polling its own management interface, for example), and a sufficiently stealthy exfiltration tool could avoid them.

---

## Docs

- [Installation & Running](docs/Install.md) — how to download the binary, run on the Pi, set up systemd, and build locally for development
- [Network Setup](docs/Routing.md) — how to route your Mac, Linux machine, Firefox, or CLI tools through Asgard

---

## Architecture

```
:8888  Raw ServerSocket   — all proxy traffic (HTTP + HTTPS CONNECT)
:8080  Nano HttpServer    — dashboard UI and REST API only
```

Asgard is a single self-contained native binary with no JVM dependency in production. It is built with [Nano](https://github.com/YunaBraska/nano), a lightweight Java service framework, and compiled to a native binary via GraalVM for the Pi's `linux/aarch64` architecture.

The proxy and dashboard are completely independent — the proxy uses raw sockets and is unaffected by anything happening on the HTTP layer.

---
