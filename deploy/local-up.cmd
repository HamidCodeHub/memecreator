@echo off
rem Local test: builds and starts the whole site with Docker Desktop.
rem With CLOUDFLARE_TUNNEL_TOKEN set in deploy\.env the site is on https://memesforlaughs.com (named tunnel);
rem without it, on a temporary https://....trycloudflare.com address (Quick Tunnel).
cd /d "%~dp0"
if not exist .env (echo deploy\.env is missing & exit /b 1)
if not exist memecreator.env (echo deploy\memecreator.env is missing & exit /b 1)
for /f "tokens=1,* delims==" %%a in ('findstr /b "AIPROXY_DIR=" .env') do set "AIPROXY_DIR=%%b"
set "FILES=-f docker-compose.yml -f docker-compose.local.yml"
findstr /r /b "CLOUDFLARE_TUNNEL_TOKEN=." .env >nul || set "FILES=%FILES% -f docker-compose.quicktunnel.yml"
set "COMPOSE=docker compose --env-file .env --env-file %AIPROXY_DIR%\.env %FILES%"
%COMPOSE% up -d --build --remove-orphans || exit /b 1
echo.
findstr /r /b "CLOUDFLARE_TUNNEL_TOKEN=." .env >nul && (
  echo Site: https://memesforlaughs.com  ^(through the Cloudflare tunnel^)
) || (
  echo Waiting for the temporary tunnel address...
  timeout /t 15 /nobreak >nul
  %COMPOSE% logs cloudflared | findstr /i "trycloudflare.com"
)
echo Local address: http://localhost:8088
echo Logs: %COMPOSE% logs -f memecreator
