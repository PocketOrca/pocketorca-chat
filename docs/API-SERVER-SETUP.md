# Enabling the Hermes API Server (server side)

PocketOrca-Chat talks to the stock `api_server` of [Hermes Agent](https://github.com/NousResearch/hermes-agent).
This is how to enable it on the machine running Hermes (any Linux box on your LAN or VPN).

## Setup

Run on the Hermes machine:

```bash
# 1. Generate a strong API key and configure the server
hermes config set API_SERVER_ENABLED true
hermes config set API_SERVER_KEY "$(openssl rand -hex 24)"   # ≥16 chars required — shorter keys are silently rejected
hermes config set API_SERVER_HOST 0.0.0.0
hermes config set API_SERVER_PORT 8642
hermes gateway restart

# 2. Verify
ss -tln | grep 8642
curl http://127.0.0.1:8642/v1/health

# 3. Show your key (paste into PocketOrca-Chat's contact form)
grep API_SERVER_KEY ~/.hermes/.env
```

These settings are written to `~/.hermes/.env`. They take effect on `hermes gateway restart` —
editing them does not restart anything by itself.

## Connect from the phone

Add a contact in PocketOrca-Chat:

- **Address** — the Hermes machine's IP, e.g. `192.168.1.100`
- **Port** — `8642` (unless you changed it above)
- **API Key** — the value from step 3

## Pitfalls

- **The key must be at least 16 characters.** A shorter key makes Hermes silently refuse to
  start the api_server: the gateway runs normally, the port never opens, and nothing is logged.
  `openssl rand -hex 24` (48 chars) always passes — hand-typed keys are where people get bitten.
- If the port does not open after restart, check the firewall (`ufw status`) and confirm the
  gateway actually restarted.
- Access logs live in `~/.hermes/logs/agent.log` (`aiohttp.access` lines), not `gateway.log`.
- A `401` on the phone means the key is wrong or was pasted truncated.

## Security

- The key is a single static bearer token: no scoping, no expiry, no per-device revocation.
  Treat it as equivalent to root on that host; rotate by re-running the `config set` line above.
- **Never expose port 8642 to the public internet.** LAN only, or reach it through a VPN
  (WireGuard, Tailscale). `API_SERVER_HOST 0.0.0.0` is for LAN use.
- Keep approval mode enabled on the server — it is what turns dangerous commands into
  confirmation cards in the app instead of instant execution.
