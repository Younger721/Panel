@echo off
rem Start backend dev on JDK 21, listening on 8081 (the port frontend/vite proxies to).
rem
rem Why this wrapper exists: the machine-level JAVA_HOME points to D:\java\jdk-8, and
rem start-backend-dev.bat only defaults JAVA_HOME to 21 when it is *undefined*, so Maven
rem would run on JDK 8 and fail with UnsupportedClassVersionError on the spring-boot
rem plugin. Pin it here for this process only; no system setting is modified.
rem
rem Keep this file ASCII-only: cmd reads .bat as the OEM codepage, and UTF-8 Chinese
rem comments get mangled into bogus commands.
setlocal
set "JAVA_HOME=D:\java\jdk-21"
set "SERVER_PORT=8081"
call "%~dp0..\start-backend-dev.bat"
endlocal
