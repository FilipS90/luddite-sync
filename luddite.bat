@echo off
setlocal enabledelayedexpansion
rem Luddite Sync — wrapper script (Windows)
rem
rem Usage:
rem   luddite.bat                        — start in server mode (default)
rem   luddite.bat client <remote-host>   — start in client mode pointing at remote-host

if "%KEYSTORE_PASSWORD%"=="" (
    set /p KEYSTORE_PASSWORD="[luddite] Enter keystore password: "
)

set SCRIPT_DIR=%~dp0
set SERVER_JAR=%SCRIPT_DIR%server-sync\target\server-sync-0.0.1-SNAPSHOT.jar
set CLIENT_JAR=%SCRIPT_DIR%client-sync\target\client-sync-0.0.1-SNAPSHOT.jar

if "%1"=="client" (set MODE=client) else (set MODE=server)

:loop
if "%MODE%"=="server" goto run_server
if "%MODE%"=="client" goto run_client
goto loop

:run_server
echo [luddite] Starting in SERVER mode...
java -Djava.net.preferIPv4Stack=true -jar %SERVER_JAR%
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE%==2 (
    echo [luddite] Shutdown signal received -- switching to CLIENT mode
    set MODE=client
    goto loop
)
if %EXIT_CODE%==3 (
    echo [luddite] Switch-mode complete -- restarting as CLIENT
    set MODE=client
    goto loop
)
echo [luddite] Server exited with code %EXIT_CODE% -- restarting in server mode in 5s...
timeout /t 5 /nobreak >nul
goto loop

:run_client
echo [luddite] Starting in CLIENT mode..
java -Djava.net.preferIPv4Stack=true -jar %CLIENT_JAR% --sync.socket.keystore=classpath:client-keystore.p12
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE%==2 (
    echo [luddite] Resume-server-mode signal received -- switching back to SERVER mode
) else (
    echo [luddite] Client exited with code %EXIT_CODE% -- switching back to SERVER mode
)
set MODE=server
goto loop
