@echo off
cd /d D:\javacode\ai-agent\ai-agent-station-study
call D:\develop\apache-maven-3.9.4\bin\mvn.cmd compile -pl ai-agent-station-study-domain -q -DskipTests
echo EXIT_CODE=%ERRORLEVEL%
pause