@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "JAVAC=javac"
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    goto compile
)
where javac >nul 2>&1
if not errorlevel 1 goto compile
for /d %%D in ("%USERPROFILE%\.jdks\*") do (
    if exist "%%D\bin\javac.exe" (
        set "JAVAC=%%D\bin\javac.exe"
        set "JAVA=%%D\bin\java.exe"
        goto compile
    )
)
echo Установите JDK 17 или новее, либо задайте JAVA_HOME.
pause
exit /b 1
:compile
if not exist out mkdir out
"%JAVAC%" --release 17 -encoding UTF-8 -d out src\*.java
if errorlevel 1 goto failure
"%JAVA%" -Dfile.encoding=UTF-8 -cp out Main %*
if errorlevel 1 goto failure
pause
exit /b 0
:failure
echo Не удалось собрать или запустить программу. Проверьте JDK и сообщение выше.
pause
exit /b 1
