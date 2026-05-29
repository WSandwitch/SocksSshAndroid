# SocksSsh Android

An Android application that creates an SSH tunnel with a built-in SOCKS5 proxy, providing a secure tunnel for your traffic.

Equivalent to `ssh -N -D <local_port> user@host -p <port>` on desktop.

## How it works

1. Connects to an SSH server using JSch (mwiede fork).
2. Starts a SOCKS5 proxy on the device (bound to `0.0.0.0:<local_port>`).
3. Configures other apps (browser, etc.) to use `127.0.0.1:<local_port>` as a SOCKS5 proxy.
4. All traffic is forwarded through the encrypted SSH tunnel.

## Features

- **SOCKS5 proxy** — custom full-duplex implementation using `getStreamForwarder` + two forwarding threads (not limited by JSch's half-duplex channel design).
- **Background service** — runs as a ForegroundService (`foregroundServiceType="dataSync"`) with a persistent notification showing connection status.
- **Healthcheck & auto-reconnect** — periodically verifies the SSH session and proxy socket are alive; reconnects automatically if the tunnel drops.
- **Auto-start on boot** — reconnects automatically after device reboot if the connection was not manually stopped.
- **Encrypted config storage** — credentials and settings saved in EncryptedSharedPreferences (AES-256-GCM).
- **In-memory logs** — session logs are kept in memory (up to ~1000 lines), no disk writes during operation.
- **Compression** — optional SSH compression toggle (equivalent to `-C`).
- **Verbose logging** — optional detailed logging for debugging.
- **Log selection** — log output is selectable for copy/paste.
- **Material 3 UI** — clean Compose interface with monospace log terminal.

## Requirements

- Android 8.0 (API 26) or higher (minSdk 26).
- Target SDK: 34 (Android 14).

## Building

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Open the app.
2. Enter your SSH server details:
   - **Server Address** — hostname or IP.
   - **Port** — SSH server port (default 22).
   - **Login** — SSH username.
   - **Password** and/or **Private Key** — authentication method.
3. Set the **Local Port** (the SOCKS5 proxy port on your device, default 1080).
4. Adjust **HC Period (s)** — how often the healthcheck runs (default 30s).
5. Toggle **Compression** or **Verbose logs** as needed.
6. Tap **Connect**.
7. Configure your app (browser, etc.) to use SOCKS5 proxy at `127.0.0.1:<local_port>`.
8. Tap **Disconnect** to stop.

## Technical notes

- Uses `com.github.mwiede:jsch:0.2.16` — no native SSH library is available for Android.
- SOCKS5 handshake is implemented manually (JSch removed `setPortForwardingL(int)` in the mwiede fork).
- The proxy binds to `0.0.0.0` so locally-originating connections from any app on the device can reach it via `127.0.0.1`.
- IPv6 addresses are rejected with a SOCKS5 error (code 8, address type not supported) — the SSH server may not support IPv6 forwarding; the client falls back to IPv4.
- All config fields (except logs) use separate text state in the UI — clearing a field does not reset it to a default until Connect is pressed.

## Limitations

- No PAC / auto-proxy configuration — you must manually set the SOCKS5 proxy in each app.
- DNS resolution happens on the SSH server (not locally) — the SOCKS5 proxy forwards the raw target address/domain through the SSH tunnel.
- Single SSH server — no multi-hop or chain proxy support.
