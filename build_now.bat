@echo off
cd /d E:\SDWMP3
call .\gradlew.bat packageRelease --max-workers=1 --no-daemon > E:\SDWMP3\build_log5.txt 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> E:\SDWMP3\build_log5.txt
