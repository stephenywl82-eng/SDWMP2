@echo off
cd /d E:\SDWMP3
call gradlew.bat --stop >nul 2>&1
call gradlew.bat clean >nul 2>&1
call gradlew.bat packageRelease --max-workers=1 > E:\SDWMP3\build_final.txt 2>&1
if %ERRORLEVEL% EQU 0 (
  echo BUILD SUCCESS >> E:\SDWMP3\build_final.txt
  C:\Android\Sdk\platform-tools\adb.exe install -r E:\SDWMP3\app\build\outputs\apk\release\app-release.apk >> E:\SDWMP3\build_final.txt 2>&1
  C:\Android\Sdk\platform-tools\adb.exe shell monkey -p com.sdw.music.player.pro -c android.intent.category.LAUNCHER 1 >> E:\SDWMP3\build_final.txt 2>&1
  echo DONE ALL >> E:\SDWMP3\build_final.txt
) else (
  echo BUILD FAILED >> E:\SDWMP3\build_final.txt
)
