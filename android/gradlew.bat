@echo off
setlocal
set "APP_HOME=%~dp0"
if not defined JAVA_HOME (
  where java >nul 2>nul
  if errorlevel 1 (
    echo Java 17 no encontrado. Configura JDK 17 en JAVA_HOME o abre la terminal de Android Studio.
    exit /b 1
  )
  java -cp "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
  "%JAVA_HOME%\bin\java.exe" -cp "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
exit /b %ERRORLEVEL%
