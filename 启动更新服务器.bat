@echo off
setlocal
chcp 65001 >nul
set PYTHONIOENCODING=utf-8
title AIQuizLite Update Server
cd /d "%~dp0"
echo ============================================================
echo   AIQuizLite Update Server (SakuraFrp mode)
echo   1. Make sure the SakuraFrp launcher is running and the
echo      "mcMC" tunnel is ON (public TCP 41717 -^> 127.0.0.1:4387)
echo   2. The current Signed Release APK is selected automatically.
echo      Prefer the agent-built release or the prepared update APK.
echo      Package, version and ORIGINAL signature are still verified.
echo      No Android Studio rebuild is needed for a prepared version.
echo   3. Optional: pass release notes as argument, e.g.
echo      %~nx0 "Fixed xxx bug"
echo ============================================================
echo.
python update_server.py %*
echo.
pause
