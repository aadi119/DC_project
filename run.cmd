@echo off
rem Console demonstrations. Switches the console to UTF-8 so the symbols print correctly.
rem   run.cmd        Experiments 4 to 8 (one connected run)
rem   run.cmd 1      Experiment 1: client-server with Java RMI
rem   run.cmd 2      Experiment 2: multithreading
rem   run.cmd 3      Experiment 3: clock synchronization (Cristian, Berkeley, Lamport)
rem   run.cmd all    Experiments 1 to 8
rem Experiments 9 and 10 (MPI): run-mpi.cmd
chcp 65001 > nul

set "MVN=mvn"
where mvn > nul 2>&1
if not %errorlevel%==0 set "MVN=C:\dcp\apache-maven-3.9.9\bin\mvn.cmd"

call %MVN% -q compile
if errorlevel 1 exit /b 1

if "%1"=="1" goto exp1
if "%1"=="2" goto exp2
if "%1"=="3" goto exp3
if "%1"=="all" goto all
goto main

:all
call %MVN% -q exec:exec -Dmain.class=RailwayRmiDemo
call %MVN% -q exec:exec -Dmain.class=RailwayMultithreadingDemo
call %MVN% -q exec:exec -Dmain.class=RailwayClockSyncDemo
goto main

:exp1
call %MVN% -q exec:exec -Dmain.class=RailwayRmiDemo
goto :eof

:exp2
call %MVN% -q exec:exec -Dmain.class=RailwayMultithreadingDemo
goto :eof

:exp3
call %MVN% -q exec:exec -Dmain.class=RailwayClockSyncDemo
goto :eof

:main
call %MVN% -q exec:exec
