@echo off
rem Web dashboard for all 10 experiments. Opens http://localhost:8080 when ready.
rem Stop the server with Ctrl+C.
chcp 65001 > nul

if "%MPJ_HOME%"=="" set "MPJ_HOME=C:\dcp\mpj-v0_44"

where mvn > nul 2>&1
if %errorlevel%==0 (
    call mvn -q compile exec:exec -Dmain.class=RailwayWebServer
) else (
    call C:\dcp\apache-maven-3.9.9\bin\mvn.cmd -q compile exec:exec -Dmain.class=RailwayWebServer
)
