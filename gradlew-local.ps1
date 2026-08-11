$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:GRADLE_USER_HOME = Join-Path $projectRoot ".gradle-user-home"
$env:ANDROID_USER_HOME = Join-Path $projectRoot ".android-user-home"

$localPropertiesPath = Join-Path $projectRoot "local.properties"
if (Test-Path -LiteralPath $localPropertiesPath) {
    $javaHomeProperty = Get-Content -LiteralPath $localPropertiesPath |
        Where-Object { $_ -match '^org\.gradle\.java\.home=' } |
        Select-Object -First 1
    if ($javaHomeProperty) {
        $javaHomeValue = $javaHomeProperty.Substring($javaHomeProperty.IndexOf('=') + 1)
        $env:JAVA_HOME = $javaHomeValue -replace '\\:', ':'
    }
}

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set and org.gradle.java.home is missing from local.properties."
}

& (Join-Path $projectRoot "gradlew.bat") @args
exit $LASTEXITCODE
