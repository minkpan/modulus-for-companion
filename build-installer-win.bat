@echo off
:: Build the Windows installer (.exe) for Modulus for Companion.
::
:: Requires:
::   - Java 17+ with jpackage (JDK 14+) – set JAVA_HOME
::   - Maven 3.9+ on PATH
::   - WiX Toolset 3.x on PATH (https://github.com/wixtoolset/wix3/releases)
::
:: Output: target\dist\Modulus for Companion-0.0.5.exe

setlocal

set DIR=%~dp0
set APP_VERSION=0.0.5
set FX_VERSION=17.0.12
set JAR=%DIR%target\modulus-for-companion-%APP_VERSION%.jar
set LIB=%DIR%target\lib
set APP=%DIR%target\app-stage
set DIST=%DIR%target\dist

:: Resolve JAVA_HOME if not set
if "%JAVA_HOME%"=="" (
    for /f "tokens=*" %%i in ('where java') do (
        set JAVA_EXE=%%i
        goto :foundJava
    )
    :foundJava
    for %%i in ("%JAVA_EXE%") do set JAVA_HOME=%%~dpi..\..
)
echo Using JAVA_HOME: %JAVA_HOME%

:: 1. Build the project
echo [1/3] Building...
call mvn -q package -f "%DIR%pom.xml"
if errorlevel 1 (echo Build failed. & exit /b 1)

:: 2. Prepare a clean staging dir with only the app JARs
echo [2/3] Preparing staging directory...
if exist "%APP%" (
    for /d %%i in ("%APP%") do rd /s /q "%%i"
)
mkdir "%APP%"
copy /y "%JAR%" "%APP%\" >nul 2>&1
copy /y "%LIB%\jackson-databind-%APP_VERSION:0.0.5=2.17.1%.jar"    "%APP%\" >nul 2>&1
xcopy /y /q "%LIB%\jackson-*.jar"  "%APP%\" >nul 2>&1
xcopy /y /q "%LIB%\snakeyaml-*.jar" "%APP%\" >nul 2>&1
:: List what we staged
dir /b "%APP%"

:: 3. Run jpackage
echo [3/3] Running jpackage...
if exist "%DIST%\Modulus for Companion" (
    rd /s /q "%DIST%\Modulus for Companion"
)

set MODS=%JAVA_HOME%\jmods;%LIB%\javafx-controls-%FX_VERSION%-win.jar;%LIB%\javafx-graphics-%FX_VERSION%-win.jar;%LIB%\javafx-base-%FX_VERSION%-win.jar

jpackage ^
  --input "%APP%" ^
  --main-jar modulus-for-companion-%APP_VERSION%.jar ^
  --main-class ModulusForCompanion ^
  --name "Modulus for Companion" ^
  --app-version %APP_VERSION% ^
  --vendor Modulus ^
  --module-path "%MODS%" ^
  --add-modules javafx.controls,javafx.graphics,javafx.base ^
  --icon "%DIR%src\main\resources\icons\app.ico" ^
  --type exe ^
  --win-menu ^
  --win-shortcut ^
  --win-dir-chooser ^
  --dest "%DIST%"

if errorlevel 1 (echo jpackage failed. & exit /b 1)

echo.
echo Done!  Installer: %DIST%\Modulus for Companion-%APP_VERSION%.exe
