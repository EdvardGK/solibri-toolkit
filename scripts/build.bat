@echo off
set JAVA_HOME=C:\Program Files\Solibri\SOLIBRI\jre
set PATH=C:\dev\tools\apache-maven-3.9.9\bin;%JAVA_HOME%\bin;%PATH%
cd /d %~dp0\..\java
mvn clean package -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)
copy /Y target\skiplum-solibri-tools-1.0-SNAPSHOT.jar "C:\Program Files\Solibri\SOLIBRI\lib\skiplum-solibri-tools.jar" >nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] Build successful. JAR deployed to Solibri. Restart Solibri to load.
) else (
    echo [ERROR] Failed to copy JAR to Solibri lib folder.
)
pause
