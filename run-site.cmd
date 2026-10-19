@echo off
rem RailNet Ops web dashboard. Opens http://localhost:8080 when ready.
rem Stop the server with Ctrl+C.
chcp 65001 > nul

if "%MPJ_HOME%"=="" set "MPJ_HOME=C:\dcp\mpj-v0_44"

rem Stop a RailNet server left over from an earlier run (it would hold the port)
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | Where-Object { $_.CommandLine -like '*RailwayWebServer*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host 'Stopped an earlier RailNet server (PID' $_.ProcessId')' }"

where mvn > nul 2>&1
if %errorlevel%==0 (
    call mvn -q compile exec:exec -Dmain.class=RailwayWebServer
) else (
    call C:\dcp\apache-maven-3.9.9\bin\mvn.cmd -q compile exec:exec -Dmain.class=RailwayWebServer
)
