@rem AltarSMP Fabric - Windows launcher. Requires a real Gradle wrapper jar or a
@rem local Gradle install. Run `gradle wrapper --gradle-version 9.5.1` once, or use
@rem the provided sh/termux launcher.
@if exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  java -Dorg.gradle.appname=gradlew -jar "%~dp0gradle\wrapper\gradle-wrapper.jar" %*
) else (
  echo gradle-wrapper.jar is missing. Run: gradle wrapper --gradle-version 9.5.1
  exit /b 1
)
