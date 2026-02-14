@echo off
cd /d "%~dp0"
javac -encoding UTF-8 -source 1.8 -target 1.8 -d out\production\VocalTrainer VocalTrainer.java
java -cp out\production\VocalTrainer VocalTrainer
pause