# HoldFast

A free, open-source Android SSH client built for one job: reaching your own computer from your phone
(for example over [Tailscale](https://tailscale.com)) and **staying connected** while the phone is locked,
in your pocket, or switching networks.

It was written as a personal replacement for paid SSH apps whose sessions kept dropping during long-running
terminal work (builds, AI coding agents, scripts). It is an original app, not a fork.

> **Status:** personal project, used daily, version 1.2.0. Expect rough edges. No warranty (see `LICENSE`).

## What you're getting

**Connecting**
- Hosts with groups, tags, search, jump hosts, and a per-host theme, font and startup command
- One-tap first login: type the password once and HoldFast creates an Ed25519 key and installs it on the
  computer (Windows OpenSSH or Linux/macOS), so later logins need no password
- Keychain: generate Ed25519 / ECDSA / RSA-4096 keys, import PEM / OpenSSH / PuTTY keys, copy, share or
  install public keys
- Port forwarding: local, remote and dynamic (SOCKS)
- Tailscale-aware: recognises 100.x / `*.ts.net` addresses

**Staying connected**
- Runs as a foreground service with wake and Wi-Fi locks and 15-second keep-alives
- Auto-reconnects with backoff into the same screen when the network drops
- A banner warns you if Android battery optimisation could kill the session
- "It's done" alerts: after you press Enter, HoldFast vibrates and notifies you when the tab goes quiet
  or rings the bell. That makes it handy for waiting on long builds or agents.

**Terminal**
- Multiple tabs (swipe between them), xterm-256color, 6 themes, pinch to zoom
- Extra-keys bar with Esc, Tab, Shift+Tab, arrows, sticky Ctrl and Alt; volume-down works as Ctrl
- Tap to pick numbered menus, open links and paste code blocks; native long-press text selection
- Keyboard autocorrect, suggestions and swipe typing work at the prompt (this can be turned off in Settings,
  because your keyboard may learn words typed at password prompts)
- Snippets with `{{variables}}`, optionally sent to every tab at once

**Files**
- SFTP browser: upload, download, edit, rename, chmod, mkdir (uploads go to a `.part` file first and
  ask before overwriting)
- Photo and file upload: use the paperclip in the terminal, or Share → HoldFast from any app. Files land in
  `~/HoldFast-uploads` and the path is pasted at the prompt.

**Security and privacy**
- Hosts, keys and passwords are stored in a vault encrypted with the Android Keystore (AES-GCM)
- App lock (biometric or PIN) and screenshot blocking
- Encrypted backup file (password-based, PBKDF2 + AES)
- Optional sync of the encrypted vault through a **private GitHub gist** that you own. This is the only
  network call the app makes besides your SSH connections. There are no analytics, ads or trackers.
- Crash reports are saved on the phone only and are never uploaded

**Starter content:** a fresh install includes a few example snippets (`claude`, `claude --continue`,
`cd {{folder}}`, `git status`). Delete them if you don't need them. No hosts are pre-filled.

## Install

Download the latest `HoldFast-N.apk` from the [Releases](../../releases) page and open it on your phone
(allow "install unknown apps" for your browser or file manager). Requires Android 8.0+.

Each release is built automatically by GitHub Actions from the code in this repo. Releases are signed with
a CI debug key that can change between builds, so if Android refuses an update, uninstall the old version
first. Export a backup from Settings before you do.

## Build it yourself

`./gradlew assembleDebug` (JDK 17, Android SDK 34). Tests: `./gradlew testDebugUnitTest`.

## Built with

- Terminal engine: [Termux](https://github.com/termux/termux-app) `terminal-emulator` and `terminal-view`
  v0.118.1 (Apache-2.0). `app/src/main/java/com/termux/terminal/RemoteTerminal.java` is a HoldFast adapter
  that feeds a Termux terminal session from an SSH stream instead of a local shell.
- SSH: [sshj](https://github.com/hierynomus/sshj) (Apache-2.0), with Bouncy Castle (MIT)
- AndroidX and Material Components (Apache-2.0)

See [CHANGES.md](CHANGES.md) for the version history.

## License

MIT. See `LICENSE`.
