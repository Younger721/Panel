@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ======================================
echo  Panel Infrastructure (Kafka)
echo ======================================
echo  Reusing shared middleware already on this machine:
echo    Redis  localhost:6379
echo    ES     localhost:9202
echo    MinIO  localhost:9000
echo    Milvus localhost:19530
echo  If they are not running, start the GymMind stack first,
echo  or point the REDIS_/ES_/MILVUS_/MINIO_ vars in .env elsewhere.
echo.

call "%~dp0scripts\load-root-env.bat"
if errorlevel 1 (
    pause
    exit /b 1
)

docker compose up -d kafka
if errorlevel 1 (
    echo [ERROR] docker compose failed
    pause
    exit /b 1
)

echo.
echo Waiting for Kafka health check (about 30s)...
docker compose ps
exit /b 0
