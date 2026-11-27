@echo off
rem Local test: builds and starts the whole site with Docker Desktop, then prints the public tunnel address.
rem Needs deploy\.env (local values) and deploy\memecreator.env next to this file.
cd /d "%~dp0"
if not exist .env (echo deploy\.env is missing & exit /b 1)
if not exist memecreator.env (echo deploy\memecreator.env is missing & exit /b 1)
for /f "tokens=1,* delims==" %%a in ('findstr /b "AIPROXY_DIR=" .env') do set "AIPROXY_DIR=%%b"
set "COMPOSE=docker compose --env-file .env --env-file %AIPROXY_DIR%\.env -f docker-compose.yml -f docker-compose.local.yml"
%COMPOSE% up -d --build || exit /b 1
echo.
echo Waiting for the Cloudflare tunnel address...
timeout /t 15 /nobreak >nul
%COMPOSE% logs cloudflared | findstr /i "trycloudflare.com"
echo.
echo Local address: http://localhost:8088   Logs: %COMPOSE% logs -f memecreator
