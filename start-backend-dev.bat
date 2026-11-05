@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ======================================
echo  Nova AI Backend (dev)
echo ======================================

call "%~dp0scripts\load-root-env.bat"
if errorlevel 1 (
    pause
    exit /b 1
)

if not defined JAVA_HOME set "JAVA_HOME=D:\java\jdk-21"
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=dev"

cd /d "%~dp0backend"
echo JAVA_HOME : %JAVA_HOME%
echo Profile   : %SPRING_PROFILES_ACTIVE%
echo Working   : %CD%
echo.

call mvn spring-boot:run
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
