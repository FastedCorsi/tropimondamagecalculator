@echo off
setlocal

set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
set WRAPPER_VERSION=9.5.0
set WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v%WRAPPER_VERSION%/gradle/wrapper/gradle-wrapper.jar
set WRAPPER_SHA256=497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)

if not exist "%CLASSPATH%" (
  echo Downloading Gradle Wrapper %WRAPPER_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $jar = '%CLASSPATH%'; [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($jar)) | Out-Null; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile $jar; $expected = '%WRAPPER_SHA256%'; $actual = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower(); if ($actual -ne $expected) { [System.IO.File]::Delete($jar); throw \"Gradle Wrapper checksum mismatch. Expected $expected, got $actual\" }"
  if errorlevel 1 exit /b 1
)

"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
