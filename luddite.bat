@echo off
rem Luddite Sync — wrapper script (Windows)
rem
rem Starts in server mode by default.
rem If the server exits with code 2 (triggered by a remote shutdown-server signal from the client),
rem it switches to client mode, using the client keystore and pointing at the remote host.
rem When the client exits normally (code 0), it switches back to server mode.
rem
rem Usage:
rem   luddite.bat                        — start in server mode (default)
rem   luddite.bat client <remote-host>   — start in client mode pointing at remote-host

set SERVER_JAR=server-sync\target\server-sync-0.0.1-SNAPSHOT.jar
set CLIENT_JAR=client-sync\target\client-sync-0.0.1-SNAPSHOT.jar

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
        echo [luddite] Swap-back signal received -- switching back to SERVER mode
    ) else (
        echo [luddite] Client exited with code %ERRORLEVEL% -- switching back to SERVER mode
    )
    set MODE=server
    goto loop
)
