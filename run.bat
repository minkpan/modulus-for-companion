@echo off
:: Run Modulus for Companion from source (Windows).
:: Requires: Java 17+, Maven 3.9+ on PATH.

setlocal

set DIR=%~dp0
set JAR=%DIR%target\modulus-for-companion-0.0.5.jar
set LIB=%DIR%target\lib

:: Build if the JAR doesn't exist yet
if not exist "%JAR%" (
    echo Building...
    call mvn -q package -f "%DIR%pom.xml"
)

set MODS=%LIB%\javafx-controls-17.0.12-win.jar;%LIB%\javafx-graphics-17.0.12-win.jar;%LIB%\javafx-base-17.0.12-win.jar

java --module-path "%MODS%" --add-modules javafx.controls,javafx.graphics,javafx.base -jar "%JAR%" %*
