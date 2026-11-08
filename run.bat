@echo off
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo javac was not found. Install a JDK and add its bin folder to PATH.
    exit /b 1
)

if not exist "lib\sqlite-jdbc.jar" (
    echo lib\sqlite-jdbc.jar was not found. Follow the setup instructions in README.md.
    exit /b 1
)

if not exist "out" mkdir "out"
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "lib\sqlite-jdbc.jar" -d out ToDoApp.java
if errorlevel 1 exit /b %errorlevel%

java --add-modules jdk.httpserver -cp "out;lib\sqlite-jdbc.jar" ToDoApp
