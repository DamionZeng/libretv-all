@echo off
cd /d "%~dp0"
set PYTHONUNBUFFERED=1
if not exist data mkdir data
powershell -NoProfile -Command "if (-not (Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue)) { Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe' }" >nul 2>&1
:loop
.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8080 --no-access-log >> data\api.log 2>&1
timeout /t 10 /nobreak >nul
goto loop
