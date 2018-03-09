@echo off
if defined ANT_HOME (

    set ANTJAR=;%ANT_HOME%\lib\ant.jar

    set ANTSRC=sjavac.ant

)

sjavac -cp %JAVA_HOME%\lib\tools.jar%ANTJAR% jdk.sjavac sjavac.transforms sjavac.test %ANTSRC% -d bin %*
