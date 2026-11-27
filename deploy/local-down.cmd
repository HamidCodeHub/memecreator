@echo off
rem Stops the local test. Data stays in the Docker volumes; add -v to the command below to wipe it.
cd /d "%~dp0"
for /f "tokens=1,* delims==" %%a in ('findstr /b "AIPROXY_DIR=" .env') do set "AIPROXY_DIR=%%b"
docker compose --env-file .env --env-file %AIPROXY_DIR%\.env -f docker-compose.yml -f docker-compose.local.yml down
