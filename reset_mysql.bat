@echo off
chcp 65001 >nul
set MYSQL_HOME=D:\mysql\mysql-8.0.42-winx64
set DATADIR=D:\mysql\mysql-8.0.42-winx64\data
set WORK=D:\javacode\ai-agent\ai-agent-station-study
set LOG=%WORK%\reset_mysql.log
set INIT=%WORK%\init.sql
set MDLOG=%WORK%\mysqld_init.log

echo === START === > "%LOG%"
echo [1/7] write init.sql >> "%LOG%"
echo FLUSH PRIVILEGES; > "%INIT%"
echo ALTER USER 'root'@'localhost' IDENTIFIED BY '123456'; >> "%INIT%"
echo FLUSH PRIVILEGES; >> "%INIT%"

echo [2/7] stop MySQL service >> "%LOG%"
net stop MySQL >> "%LOG%" 2>&1
ping 127.0.0.1 -n 4 >nul

echo [3/7] kill any leftover mysqld >> "%LOG%"
taskkill /F /IM mysqld.exe >> "%LOG%" 2>&1
ping 127.0.0.1 -n 3 >nul

echo [4/7] start mysqld with init-file + skip-grant-tables >> "%LOG%"
start "" /B "%MYSQL_HOME%\bin\mysqld.exe" --skip-grant-tables --shared-memory --datadir="%DATADIR%" --init-file="%INIT%" --console > "%MDLOG%" 2>&1

echo [5/7] waiting for mysqld ready (up to 60s) >> "%LOG%"
set /a cnt=0
:WAIT
ping 127.0.0.1 -n 4 >nul
set /a cnt+=3
findstr /C:"ready for connections" "%MDLOG%" >nul 2>&1
if %errorlevel%==0 goto READY
if %cnt% geq 60 goto TIMEOUT
goto WAIT
:READY
echo mysqld ready after %cnt%s >> "%LOG%"
goto KILL
:TIMEOUT
echo TIMEOUT waiting for mysqld >> "%LOG%"
echo --- mysqld log --- >> "%LOG%"
type "%MDLOG%" >> "%LOG%" 2>&1

:KILL
echo [6/7] kill init mysqld, start service >> "%LOG%"
taskkill /F /IM mysqld.exe >> "%LOG%" 2>&1
ping 127.0.0.1 -n 5 >nul
net start MySQL >> "%LOG%" 2>&1
ping 127.0.0.1 -n 5 >nul

echo [7/7] verify password 123456 >> "%LOG%"
"%MYSQL_HOME%\bin\mysql.exe" -uroot -p123456 -h 127.0.0.1 --execute="SELECT 'PASSWORD_RESET_OK' AS result, VERSION() AS version;" >> "%LOG%" 2>&1

echo === DONE === >> "%LOG%"
echo.
echo ===== 完成,详见 reset_mysql.log =====
pause
