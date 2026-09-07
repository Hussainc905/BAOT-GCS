@echo off
setlocal
set "GRADLE_VERSION=8.9"
if "%GRADLE_USER_HOME%"=="" (
  set "BASE=%USERPROFILE%\.gradle\101gcs-distributions"
) else (
  set "BASE=%GRADLE_USER_HOME%\101gcs-distributions"
)
set "GRADLE_DIR=%BASE%\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%BASE%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  echo 101 GCS: Gradle %GRADLE_VERSION% is not cached; downloading it once...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'; Expand-Archive -Force -Path '%ZIP_FILE%' -DestinationPath '%BASE%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_DIR%\bin\gradle.bat" %*
exit /b %errorlevel%
