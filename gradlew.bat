@echo off
setlocal
set APP_HOME=%~dp0
set GRADLE_JAVA_HOME=%JAVA_HOME%
if "%GRADLE_JAVA_HOME%"=="" (
    for /f "tokens=*" %%i in ('where java 2^>nul') do (
        set GRADLE_JAVA_HOME=%%~dpi
        set GRADLE_JAVA_HOME=%GRADLE_JAVA_HOME:~0,-1%
    )
)
"%GRADLE_JAVA_HOME%\bin\java.exe" -Dorg.gradle.jvmargs="-Xmx4g" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
