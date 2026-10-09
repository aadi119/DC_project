@echo off
rem Experiments 9 and 10: MPI programs (MPJ Express, multicore mode, 4 processes).
rem   run-mpi.cmd       runs both experiments
rem   run-mpi.cmd 9     runs Experiment 9 only
rem   run-mpi.cmd 10    runs Experiment 10 only
chcp 65001 > nul

if "%MPJ_HOME%"=="" set "MPJ_HOME=C:\dcp\mpj-v0_44"
if not exist "%MPJ_HOME%\lib\starter.jar" (
    echo MPJ Express not found. Set MPJ_HOME to the MPJ Express folder.
    exit /b 1
)

where mvn > nul 2>&1
if %errorlevel%==0 (
    call mvn -q compile
) else (
    call C:\dcp\apache-maven-3.9.9\bin\mvn.cmd -q compile
)

set "MPJRUN=java -Dstdout.encoding=UTF-8 -jar "%MPJ_HOME%\lib\starter.jar" -np 4 -cp target\classes"

if not "%1"=="10" %MPJRUN% RailwayMpiCollectives
if not "%1"=="9" %MPJRUN% RailwayMatrixMultiplication
