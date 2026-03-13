# Installation & Running

This guide covers how to get Asgard running on a Raspberry Pi Zero 2W, and how to build and run it locally for development.

---

## Production — Raspberry Pi

### Requirements

- Raspberry Pi Zero 2W (or any Linux aarch64 device)
- Raspberry Pi OS Lite (64-bit) recommended
- Connected to your local network via Wi-Fi
- No JVM required — the binary is fully self-contained

### Download

Download the latest release binary directly onto your Pi:

```bash
curl -L https://github.com/absketches/asgard/releases/latest/download/asgard -o asgard
chmod +x asgard
```

### Run

```bash
./asgard
```

Asgard will start with default settings:

| Service | Address |
|---|---|
| Proxy | `0.0.0.0:8888` |
| Dashboard | `http://<pi-ip>:8080/asgard` |

The SQLite database is created automatically at `~/asgard.db` on first run.

### Run in the background

To keep Asgard running after you close your SSH session:

```bash
nohup ./asgard > ~/.asgard/asgard.log 2>&1 &
echo $! > ~/.asgard/asgard.pid
```

To check if it is running:

```bash
cat ~/.asgard/asgard.pid | xargs ps -p
```

To stop it:

```bash
kill $(cat ~/.asgard/asgard.pid)
```

### Run on boot with systemd

For a more robust setup that survives reboots:

```bash
sudo nano /etc/systemd/system/asgard.service
```

Paste the following, replacing `/home/pi` with your actual home directory:

```ini
[Unit]
Description=Asgard Traffic Monitor
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/home/pi/asgard
Restart=on-failure
RestartSec=5
User=pi
WorkingDirectory=/home/pi
StandardOutput=append:/home/pi/.asgard/asgard.log
StandardError=append:/home/pi/.asgard/asgard.log

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable asgard
sudo systemctl start asgard
```

Check status and logs:

```bash
sudo systemctl status asgard
tail -f ~/.asgard/asgard.log
```

---

## Configuration

All settings are optional — Asgard runs without any configuration file.

To override defaults, pass them as system properties:

```bash
./asgard -Dasgard_proxy_port=9999 -Dasgard_http_port=9090
```

| Property | Default               | Description |
|---|-----------------------|---|
| `asgard_proxy_port` | `8888`                | Proxy listener port |
| `asgard_http_port` | `8080`                | Dashboard port |
| `asgard_db_path` | `~/.asgard/asgard.db` | SQLite database path |
| `asgard_max_requests` | `10000`               | Max rows before oldest are dropped |
| `asgard_beacon_threshold` | `60`                  | Requests/min to same unknown host before EXFILTRATION_RISK |
| `asgard_exfil_size_bytes` | `1048576`             | Upload size threshold for EXFILTRATION_RISK (1 MB) |
| `asgard_oisd_refresh_s` | `86400`               | oisd.nl blocklist refresh interval in seconds |

---

## Local Development — macOS / Linux

### Requirements

- Java 21+
- Maven 3.9+

### Run without building

```bash
mvn exec:java
```

### Build and run fat JAR

```bash
mvn -Pdev package
java --enable-native-access=ALL-UNNAMED -jar target/asgard.jar
```

### Build native binary (requires GraalVM)

Install GraalVM JDK 21 with `native-image`:

```bash
sdk install java 21-graalce   # using SDKMAN
```

Then build:

```bash
mvn -Pnative-linux-aarch64 package
./target/asgard
```

> **Note:** The native profile targets `linux-aarch64` (Raspberry Pi). To build a native binary for your local machine, you would need to adjust the `<platform>` in `pom.xml` or use cross-compilation via Docker. For local development the fat JAR (`-Pdev`) is recommended.

### Run tests

```bash
mvn test
```

---

## Upgrading

To upgrade to a newer release on the Pi, stop the current process, download the new binary, and restart:

```bash
# Stop
kill $(cat ~/.asgard/asgard.pid)
# or: sudo systemctl stop asgard

# Download new binary
curl -L https://github.com/absketches/asgard/releases/latest/download/asgard -o asgard
chmod +x asgard

# Restart
nohup ./asgard > ~/.asgard/asgard.log 2>&1 &
echo $! > ~/.asgard/asgard.pid
# or: sudo systemctl start asgard
```

The database is preserved across upgrades — `~/.asgard/asgard.db` is never touched during installation.
