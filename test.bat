rmdir %TEMP%\test /s /q

mkdir %TEMP%\test

cd %TEMP%\test
%JAVA_HOME%\bin\
java.exe -ea -classpath %~dp0bin;%~dp0sjavac.jar;%JAVA_HOME%\lib\tools.jar sjavac.test.SJavac

cd %~dp0