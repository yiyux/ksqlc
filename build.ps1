# Empaqueta ksqlc en Windows: instalador (.msi) + app portable (zip) en dist\.
# Requiere JDK 21 en JAVA_HOME.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

.\gradlew.bat :app:packageDistributionForCurrentOS :app:createDistributable
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$bin = 'app\build\compose\binaries\main'
Remove-Item dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item dist -ItemType Directory | Out-Null
Copy-Item "$bin\msi\*.msi" dist\
Compress-Archive -Path "$bin\app\*" -DestinationPath dist\ksqlc-Windows-x64.zip
Get-ChildItem dist
