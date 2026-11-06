@echo off
set DIR=%~dp0
if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo Missing gradle-wrapper.jar. Run scripts\setup_and_build.py
  exit /b 1
)
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
