@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ======================================
echo  Nova AI Infrastructure (Redis / ES / etcd / MinIO / Milvus)
echo ======================================

call "%~dp0scripts\load-root-env.bat"
if errorlevel 1 (
    pause
    exit /b 1
)

docker compose up -d redis elasticsearch etcd minio milvus
if errorlevel 1 (
    echo [ERROR] docker compose failed
    pause
    exit /b 1
)

echo.
echo Waiting for health checks (about 60-120s on first run)...
docker compose ps
exit /b 0
