@echo off
rem Luddite Sync — wrapper script (Windows)
rem
rem Place this script in the same directory as both JARs:
rem   server-sync-0.0.1-SNAPSHOT.jar
rem   client-sync-0.0.1-SNAPSHOT.jar
rem
rem Usage:
rem   luddite.bat                        — start in server mode (default)
rem   luddite.bat client <remote-host>   — start in client mode pointing at remote-host

set SCRIPT_DIR=%~dp0
set SERVER_JAR=%SCRIPT_DIR%server-sync-0.0.1-SNAPSHOT.jar
set CLIENT_JAR=%SCRIPT_DIR%client-sync-0.0.1-SNAPSHOT.jar

if "%1"=="client" (set MODE=client) else (set MODE=server)
if "%2"=="" (set REMOTE_HOST=localhost) else (set REMOTE_HOST=%2)

:loop
if "%MODE%"=="server" (
    echo [luddite] Starting in SERVER mode...
    java -jar %SERVER_JAR%
    if %ERRORLEVEL%==2 (
        echo [luddite] Shutdown signal received -- switching to CLIENT mode (connecting to %REMOTE_HOST%)
        set MODE=client
        goto loop
    )
    echo [luddite] Server exited with code %ERRORLEVEL% -- restarting in server mode in 5s...
    timeout /t 5 /nobreak >nul
    goto loop
)

if "%MODE%"=="client" (
    echo [luddite] Starting in CLIENT mode (connecting to %REMOTE_HOST%)...
    java -jar %CLIENT_JAR% --sync.server.host=%REMOTE_HOST% --sync.socket.keystore=classpath:client-keystore.p12
    if %ERRORLEVEL%==2 (
        echo [luddite] Resume-server-mode signal received -- switching back to SERVER mode
    ) else (
        echo [luddite] Client exited with code %ERRORLEVEL% -- switching back to SERVER mode
    )
    set MODE=server
    goto loop
)
