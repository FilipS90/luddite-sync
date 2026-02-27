@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set SERVER_YML=%SCRIPT_DIR%server-sync\src\main\resources\application.yml
set CLIENT_YML=%SCRIPT_DIR%client-sync\src\main\resources\application.yml
set CERT_CHECK=%SCRIPT_DIR%server-sync\src\main\resources\server-keystore.p12
set CERT_SCRIPT=%SCRIPT_DIR%common-sync\certs_setup.sh

echo.
echo =======================================
echo    Luddite Sync -- Installation Setup
echo =======================================
echo.

rem ── Prompt for values ────────────────────────────────────────────────────────

set /p DUCK_DOMAIN="  DuckDNS subdomain (without .duckdns.org) [luddite-sync]: "
if "%DUCK_DOMAIN%"=="" set DUCK_DOMAIN=luddite-sync

:token_prompt
set /p DUCK_TOKEN="  DuckDNS token: "
if "%DUCK_TOKEN%"=="" (
    echo   Token cannot be empty.
    goto token_prompt
)

:password_prompt
set /p KEYSTORE_PASSWORD="  Keystore password: "
if "%KEYSTORE_PASSWORD%"=="" (
    echo   Password cannot be empty.
    goto password_prompt
)

:confirm_prompt
set /p KEYSTORE_PASSWORD_CONFIRM="  Confirm keystore password: "
if not "%KEYSTORE_PASSWORD%"=="%KEYSTORE_PASSWORD_CONFIRM%" (
    echo   Passwords do not match. Try again.
    goto password_prompt
)

rem ── Certificates ─────────────────────────────────────────────────────────────

echo.
if exist "%CERT_CHECK%" (
    echo   Certificates already exist -- skipping generation
) else (
    echo   Generating certificates...
    echo   NOTE: Certificate generation requires Git Bash or WSL with openssl and keytool available.
    echo   Running: bash %CERT_SCRIPT% %KEYSTORE_PASSWORD%
    bash "%CERT_SCRIPT%" "%KEYSTORE_PASSWORD%"
    if %ERRORLEVEL% neq 0 (
        echo   ERROR: Certificate generation failed. Make sure Git Bash or WSL is installed.
        exit /b 1
    )
    echo   Certificates generated
)

rem ── Update server application.yml ────────────────────────────────────────────

echo.
echo   Updating server configuration...

powershell -Command "(Get-Content '%SERVER_YML%') -replace 'domain:.*', 'domain: %DUCK_DOMAIN%' | Set-Content '%SERVER_YML%'"
powershell -Command "(Get-Content '%SERVER_YML%') -replace 'token:.*', 'token: %DUCK_TOKEN%' | Set-Content '%SERVER_YML%'"
powershell -Command "(Get-Content '%SERVER_YML%') -replace 'password:.*', 'password: %KEYSTORE_PASSWORD%' | Set-Content '%SERVER_YML%'"

echo   Server configuration updated

rem ── Update client application.yml ────────────────────────────────────────────

echo.
echo   Updating client configuration...

powershell -Command "(Get-Content '%CLIENT_YML%') -replace 'host:.*', 'host: %DUCK_DOMAIN%.duckdns.org' | Set-Content '%CLIENT_YML%'"
powershell -Command "(Get-Content '%CLIENT_YML%') -replace 'password:.*', 'password: %KEYSTORE_PASSWORD%' | Set-Content '%CLIENT_YML%'"

echo   Client configuration updated

rem ── Done ─────────────────────────────────────────────────────────────────────

echo.
echo =======================================
echo    Setup complete!
echo.
echo    Next steps:
echo    1. Build:  mvnw.cmd clean package -DskipTests
echo.
echo    Start server machine:  luddite.bat
echo    Start client machine:  luddite.bat client ^<server-host^>
echo =======================================
echo.

endlocal

