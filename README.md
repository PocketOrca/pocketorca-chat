# PocketOrca-Chat

An Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent) that talks
directly to your own `api_server` over LAN or a private VPN. No middleman service, no Firebase,
no Google Play Services.

> **Maintenance status: archived / as-is.**
> This project is finished and will not be actively maintained. Issues and PRs may go unanswered.
> It exists because it was useful to one person and might be useful to someone else. Install it
> over an existing copy; do not uninstall first if you want to keep your local chat history.

## What it is

A WeChat-shaped chat app whose contacts are Hermes endpoints. Each contact is an address, a port
and an `API_SERVER_KEY`. Open a conversation, type, and you are talking to the agent running on
that machine — including tool calls, approval prompts, and images.

It was built around one constraint: **zero changes to the Hermes server.** Everything happens over
the stock `api_server` HTTP API. If you run Hermes 0.2x or later with `api_server` enabled, this
client should just work.

## What it is not

- Not an official Nous Research product. No affiliation.
- Not a wrapper around Hermes WebUI or the agent dashboard.
- Not a replacement for the desktop app. It is a phone-shaped remote control.
- Not a push-notification client. There is no FCM and no vendor push channel, by design.

## Features

- Multiple contacts — one per Hermes endpoint, each with its own key
- Session-per-contact, with a local SQLite transcript that survives app restarts
- Streaming is deliberately off: replies arrive whole, WeChat-style
- Approval cards: dangerous commands raised by the agent appear as tappable prompts (`once` / `session` / `always` / `deny`)
- Image send and receive (images come back as `MEDIA:` paths, rendered inline)
- Background polling with configurable interval, foreground service, boot restart
- Slash commands handled on the device: `/help` `/new` `/clear` `/stop` `/model` `/context` `/status` `/search`
- Cross-contact history search
- Keystore-backed credential storage (AES/GCM, per-contact key)
- Font size control, light/dark following the system

## Known limitations

Read these before opening an issue that will not be answered.

- **Background latency is not WeChat-grade.** Without a push channel, delivery depends on polling.
  On Android 12+ Doze will stretch the interval — measured around 9 minutes with the screen off on
  a Samsung S25. A foreground service with `setExactAndAllowWhileIdle` gets it down to 1-2 minutes
  at the cost of battery. This is a physical consequence of the design, not a bug to be fixed.
- **Cleartext HTTP is allowed** (`usesCleartextTraffic="true"`). The design assumes the link is
  already encrypted by your VPN or that you are on a trusted LAN. See the security section.
- **The context-usage bar needs a server-side patch.** Without it the bar stays hidden and nothing
  else breaks. The patch is on `gateway/platforms/api_server.py` and is overwritten by every
  Hermes upgrade — re-apply after upgrading, or just live without the bar.
- `api_server` has no model catalog endpoint, so `/model` accepts a free-text `provider/model`
  string. A bad model name locks the session until you set a good one.
- Only tested on Android 13/14 (Samsung One UI). `minSdk 29`, `targetSdk 36`.
- No iOS version. No plan for one.

## Install

Grab the APK from [Releases](../../releases) and install it. It is signed; updating over an older
version keeps your data.

On first launch, add a contact:

- **Name** — anything
- **Address** — the host running Hermes, e.g. `192.168.1.100` (scheme optional, defaults to `http`)
- **Port** — `8642` unless you changed it
- **API Key** — the `API_SERVER_KEY` from that machine

### Server side

In `~/.hermes/.env` on the machine running Hermes:

```
API_SERVER_ENABLED=true
API_SERVER_KEY=<a long random string>
```

Then restart the gateway. The key is a single static bearer token: it cannot be revoked
individually and every request it authorises is logged only as a plain HTTP access line.

Full setup walkthrough (commands, key generation, pitfalls, security notes):
[docs/API-SERVER-SETUP.md](docs/API-SERVER-SETUP.md).

## Security — please actually read this

Connecting this app to a Hermes endpoint means **this app can ask that machine to run commands.**
The approval UI is the only thing standing between a tap and a shell.

1. **Never expose `api_server` to the public internet.** Not with port forwarding, not with a
   public reverse proxy, not "just for a minute". Bind it to your LAN or to a VPN interface
   (WireGuard, Tailscale, whatever you already trust).
2. **Treat `API_SERVER_KEY` as equivalent to root** on that host. One static token, no scoping,
   no expiry, no per-device revocation. If it leaks, rotate it by editing `.env` and restarting.
3. **Keep approval mode on.** If you disable command approval on the server, this app becomes a
   remote shell with no confirmation step.
4. Traffic is plain HTTP inside your tunnel. That is a deliberate trade — it keeps setup to one
   IP box — and it is only acceptable because the tunnel already encrypts.
5. Local keys are encrypted with an Android Keystore key per contact. This protects against casual
   file access and backup extraction. It does not protect against a rooted device with a Keystore
   bypass, and it was never meant to.

## Build

There is no Gradle project — the build is a hand-rolled `aapt2` + `d8` + `apksigner` chain, kept
because it is fast and has no dependency resolution. You need JDK 17+, Android build-tools 34+,
and `android.jar` for API 36.

```sh
export SDK_BUILD_TOOLS=$HOME/android/sdk/build-tools/36.0.0
export ANDROID_JAR=$HOME/android/sdk/platforms/android-36/android.jar
export KS=$HOME/my-release.keystore
export KS_PASS=...
export KEY_ALIAS=...

VC=39 VN=0.5.7 ./build.sh
```

The script fails if `assets/app.js` inside the APK does not hash-match the source file, so a
silently stale build cannot ship.

## Layout

```
app/src/main/java/com/pocketorca/chat/
  MainActivity.java     WebView host, JS bridge, activity lifecycle
  PollScheduler.java    foreground service, alarm-driven polling, notifications
  HermesAdapter.java    HTTP client for the Hermes api_server
  ChatDB.java           local transcript (SQLite, WAL, dedup by message id)
  Store.java            contacts and settings
  CredentialVault.java  Keystore-backed token storage
  MediaSaver.java       save received images to the gallery
  BootReceiver.java     restart polling after reboot
app/src/main/assets/
  index.html, app.js    the entire UI — no framework, no build step
```

The UI is a single HTML file plus one JS file, opened in a hardened WebView. That was a
deliberate choice for a tool that had to be edited on-device sometimes.

## Related projects

If you want a Hermes phone client that is still maintained, look at
[rusty4444/hermes-android](https://github.com/rusty4444/hermes-android) (Flutter, supports both the
REST and the gateway JSON-RPC transports) or
[Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile). Nous Research also runs a closed
beta of an official mobile client. Those are better choices for most people; this one exists mainly
because its author wanted multiple contacts and zero server patches.

## License

MIT. See [LICENSE](LICENSE).
