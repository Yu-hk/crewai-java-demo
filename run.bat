@echo off
chcp 65001 > nul
cd /d %~dp0
if exist out rmdir /s /q out
mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
del sources.txt
java -Dfile.encoding=UTF-8 -cp out io.example.crew.Main %*
