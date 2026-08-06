@echo off
cd /d E:\SDWMP3
call .\gradlew.bat assembleRelease > E:\SDWMP3\build_log_v6b.txt 2>&1
echo DONE >> E:\SDWMP3\build_log_v6b.txt
