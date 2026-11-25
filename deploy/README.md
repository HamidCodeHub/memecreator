# Deploying memesforlaughs.com

One Oracle Cloud Always Free Arm server (VM.Standard.A1.Flex, 2 OCPU / 12 GB, Ubuntu 24.04, Milan) runs everything
with Docker Compose. Cloudflare sits in front: DNS, HTTPS and a Cloudflare Tunnel, so the server opens no ports.

```
browser ─► Cloudflare ─► tunnel ─► cloudflared ─► web (Caddy: React app, /api /oauth2 /login /generated /s/)
                                                        └─► memecreator :8081 ─► aiproxy :8080 ─► Gemini / Groq
                                                                                      └─► postgres
```

| Path on the server | What |
|---|---|
| `/srv/memesforlaughs/{memecreator,memecreator-fe,aiproxy}` | the three repositories |
| `/data/memecreator/templates` | hand-picked templates (`E:\photos\meme`) |
| `/data/memecreator/meme-storage` | harvested templates (`E:\photos\meme-storage`); the daily harvest refills it |
| `/data/memecreator/{output,private,db}` | memes, private photos + API key hashes, H2 accounts database |
| `/data/postgres` | aiproxy's database |

## First time

1. Create a read-only deploy key for the private aiproxy repository on the server:
   `ssh-keygen -t ed25519 -N "" -f ~/.ssh/aiproxy_deploy -C memesforlaughs-server`, then add
   `~/.ssh/aiproxy_deploy.pub` under GitHub → aiproxy → Settings → Deploy keys (read-only).
2. `curl -fsSL https://raw.githubusercontent.com/HamidCodeHub/memecreator/main/deploy/server-setup.sh | bash`
   (installs Docker, creates the folders, clones the repositories, generates `deploy/.env`).
3. Log out and in again, then fill in `deploy/.env` (Gemini key from a project **with billing**, Groq key,
   Cloudflare tunnel token) and `deploy/memecreator.env` (sign-in secrets, admin email, mail).
4. Copy the templates into `/data/memecreator/templates` (owner 10001).
5. `cd /srv/memesforlaughs/memecreator/deploy && docker compose up -d --build`
6. Cloudflare → Zero Trust → Networks → Tunnels: public hostname `memesforlaughs.com` (and `www`) → `http://web:80`.

## Updating

```bash
cd /srv/memesforlaughs
git -C memecreator pull && git -C memecreator-fe pull && git -C aiproxy pull
cd memecreator/deploy && docker compose up -d --build
```

Logs: `docker compose logs -f memecreator`. Restarting memecreator signs everyone out (sessions live in memory).
