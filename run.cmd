@echo off
rem Switch the console to UTF-8 so the symbols print correctly, then build and run.
chcp 65001 > nul

where mvn > nul 2>&1
if %errorlevel%==0 (
    call mvn -q compile exec:exec
) else (
    call C:\dcp\apache-maven-3.9.9\bin\mvn.cmd -q compile exec:exec
)
