@echo off
setlocal
powershell -ExecutionPolicy Bypass -File "%~dp0update-upstreams.ps1" %*
exit /b %ERRORLEVEL%
