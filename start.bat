@echo off
REM ============================================================
REM  Study Grind v2.0 — One-Click Start Script (Windows)
REM  Run this from inside the StudyPlannerApp project folder.
REM ============================================================

set APP=StudyPlannerApp
set LIB=lib\sqlite-jdbc.jar
set OUT=build
set JAR=%APP%.jar
set SQLITE_URL=https://github.com/xerial/sqlite-jdbc/releases/download/3.47.1.0/sqlite-jdbc-3.47.1.0.jar

echo.
echo ============================================
echo    Study Grind v2.0 - Quick Start
echo ============================================
echo.

REM ── Step 1: Check Java ──────────────────────────────────────
echo [ 1 / 4 ]  Checking Java...
javac -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo   X  Java not found. Please install JDK 11 or higher.
    echo.
    echo   Download: https://adoptium.net/temurin/releases/
    echo.
    pause
    exit /b 1
)
for /f "tokens=2" %%v in ('javac -version 2^>^&1') do set JAVA_VER=%%v
echo   OK  javac %JAVA_VER% found

REM ── Step 2: Auto-detect Java source file location ───────────
echo.
echo [ 1.5/4 ]  Locating source file...
if exist "src\%APP%.java" (
    set SRC=src\%APP%.java
    echo   OK  Found at src\%APP%.java
) else if exist "%APP%.java" (
    set SRC=%APP%.java
    echo   OK  Found at %APP%.java
) else (
    echo   X  Cannot find %APP%.java
    echo      Make sure start.bat is in the same folder as the project files.
    pause
    exit /b 1
)

REM ── Step 3: Auto-download SQLite JDBC if missing ────────────
echo.
echo [ 2 / 4 ]  Checking SQLite JDBC driver...
if exist "%LIB%" (
    echo   OK  sqlite-jdbc.jar already in lib\
) else (
    echo   -^>  lib\sqlite-jdbc.jar not found - downloading...
    if not exist lib mkdir lib
    powershell -Command "Invoke-WebRequest -Uri '%SQLITE_URL%' -OutFile '%LIB%'" 2>nul
    if not exist "%LIB%" (
        echo.
        echo   X  Download failed. Please download manually:
        echo      %SQLITE_URL%
        echo      Save as: lib\sqlite-jdbc.jar
        pause
        exit /b 1
    )
    echo   OK  Downloaded sqlite-jdbc.jar
)

REM ── Step 4: Compile ─────────────────────────────────────────
echo.
echo [ 3 / 4 ]  Compiling...
if not exist %OUT% mkdir %OUT%
javac -cp "%LIB%" -d "%OUT%" --release 15 "%SRC%"
if errorlevel 1 (
    echo   X  Compilation failed. See errors above.
    pause
    exit /b 1
)
echo   OK  Compiled successfully

REM ── Step 5: Build fat JAR ────────────────────────────────────
echo.
echo [ 4 / 4 ]  Packaging JAR...
cd %OUT%
jar xf "..\%LIB%"
cd ..
echo Main-Class: %APP% > manifest.txt
echo Class-Path: . >> manifest.txt
jar cfm %JAR% manifest.txt -C %OUT% .
del manifest.txt
if errorlevel 1 (
    echo   X  JAR creation failed.
    pause
    exit /b 1
)
echo   OK  %JAR% created

REM ── Launch ──────────────────────────────────────────────────
echo.
echo ============================================
echo   Build complete! Launching app...
echo ============================================
echo.
java -jar %JAR%
