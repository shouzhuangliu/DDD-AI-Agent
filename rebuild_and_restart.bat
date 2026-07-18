@echo off
chcp 65001 >nul
echo 检查 Maven ...
if not exist "D:\develop\apache-maven-3.9.4\bin\mvn.cmd" (
    echo 没找到 Maven！
    dir D:\develop\apache-maven-3.9.4\bin\ 2>nul
    pause
    exit /b
)
echo Maven 路径 OK
set MVN=D:\develop\apache-maven-3.9.4\bin\mvn.cmd

echo ===== 编译 domain =====
%MVN% compile -pl ai-agent-station-study-domain -q -DskipTests
if errorlevel 1 ( echo 编译 domain 失败 & pause & exit /b )
echo ✅ domain

echo ===== 编译 trigger =====
%MVN% compile -pl ai-agent-station-study-trigger -q -DskipTests
if errorlevel 1 ( echo 编译 trigger 失败 & pause & exit /b )
echo ✅ trigger

echo ===== 编译 app =====
%MVN% compile -pl ai-agent-station-study-app -q -DskipTests
if errorlevel 1 ( echo 编译 app 失败 & pause & exit /b )
echo ✅ app

echo ===== 停旧进程 =====
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8091') do (
    taskkill /F /PID %%a >nul 2>&1
)
echo ✅ 已停旧进程

echo ===== 打包 =====
%MVN% install -pl ai-agent-station-study-app -DskipTests -q
if errorlevel 1 ( echo 打包失败 & pause & exit /b )
echo ✅ 打包成功

echo ===== 启动 =====
start "" java -jar D:\javacode\ai-agent\ai-agent-station-study\ai-agent-station-study-app\target\ai-agent-station-study-app.jar --spring.profiles.active=dev
echo 等待 15 秒 ...
timeout /t 15 /nobreak >nul
echo ===== 验证 =====
curl -o nul -s -w "Dashboard: %%{http_code}\n" http://127.0.0.1:8091/api/v1/dashboard/stats
echo 请打开 http://localhost:8091/
pause