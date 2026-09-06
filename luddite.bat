@echo off
setlocal enabledelayedexpansion
rem Luddite Sync — wrapper script (Windows)
rem
rem Usage:
rem   luddite.bat                        — start in server mode (default)
rem   luddite.bat client <remote-host>   — start in client mode pointing at remote-host

rem Switch console to UTF-8 so Cyrillic (and other non-ASCII) paths work correctly
chcp 65001 >nul

set SCRIPT_DIR=%~dp0
set SERVER_JAR=%SCRIPT_DIR%server-sync\target\server-sync-0.0.1-SNAPSHOT.jar
set CLIENT_JAR=%SCRIPT_DIR%client-sync\target\client-sync-0.0.1-SNAPSHOT.jar

if "%1"=="client" (set MODE=client) else (set MODE=server)

:loop
if "%MODE%"=="server" goto run_server
if "%MODE%"=="client" goto run_client
goto :eof

:run_server
echo [luddite] Starting in SERVER mode...
java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar %SERVER_JAR%
set EXIT_CODE=%ERRORLEVEL%
goto :eof

:run_client
echo [luddite] Starting in CLIENT mode...
java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar %CLIENT_JAR%
set EXIT_CODE=%ERRORLEVEL%
goto :eof