@echo off

call gradlew.bat makejar
move /Y build\libs\RobotManager-1.0-SNAPSHOT.jar build\RobotManager.jar
