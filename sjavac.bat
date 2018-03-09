@echo off
IF NOT DEFINED JAVA_HOME (
    echo You have to set JAVA_HOME to run sjavac
    exit /b 0
)
@%JAVA_HOME%\bin\java.exe -ea -classpath %JAVA_HOME%\lib\tools.jar;%~dp0sjavac.jar com.sun.tools.sjavac.Main %*