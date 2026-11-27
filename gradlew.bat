@echo off
setlocal
set "GV=8.13"
set "GD=%USERPROFILE%\.gradle\quotevault-gradle-%GV%"
set "GE=%GD%\gradle-%GV%\bin\gradle.bat"
if not exist "%GE%" (
  if not exist "%GD%" mkdir "%GD%"
  echo Downloading Gradle %GV% (one-time setup)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GV%-bin.zip' -OutFile '%GD%\gradle.zip'; Expand-Archive -Path '%GD%\gradle.zip' -DestinationPath '%GD%' -Force; Remove-Item '%GD%\gradle.zip'"
  if errorlevel 1 exit /b 1
)
call "%GE%" %*
exit /b %ERRORLEVEL%
