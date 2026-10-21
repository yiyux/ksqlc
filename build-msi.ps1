# Genera el instalador .msi (y el zip portable) en dist\ desde cualquier sesión de Windows, también por SSH.
# WiX (light.exe) valida el paquete con el servicio Windows Installer y falla (código 216) fuera de una sesión de
# escritorio. Por SSH el script se vuelve a lanzar como tarea programada interactiva en la sesión abierta del usuario
# (RDP o consola); si no hay ninguna, avisa.
# Requiere JDK 21 y WiX 3.x (candle/light). Uso:  .\build-msi.ps1 [-JavaHome C:\jdk21] [-WixDir C:\wix]
param(
    [string]$JavaHome,
    [string]$WixDir,
    [switch]$InSession   # interno: ya estamos en la sesión de escritorio
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$log = Join-Path $PSScriptRoot 'build-msi.log'
$done = Join-Path $PSScriptRoot 'build-msi.done'

if (-not $InSession -and -not $env:SESSIONNAME) {
    # sin SESSIONNAME no hay sesión de escritorio (SSH, servicio): delegar en una que sí lo sea
    $user = "$env:USERDOMAIN\$env:USERNAME"
    if (-not (query user 2>$null | Select-String "^\s*>?\s*$([regex]::Escape($env:USERNAME))\s+(console|rdp)" )) {
        throw "No hay una sesión de escritorio abierta de ${user}: entra por RDP o en la consola y vuelve a lanzar el script."
    }
    Remove-Item $log, $done -ErrorAction SilentlyContinue
    $cmd = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -InSession"
    if ($JavaHome) { $cmd += " -JavaHome `"$JavaHome`"" }
    if ($WixDir) { $cmd += " -WixDir `"$WixDir`"" }
    $task = 'ksqlc-build-msi'
    schtasks /create /tn $task /tr $cmd /sc once /st 00:00 /it /f | Out-Null
    try {
        schtasks /run /tn $task | Out-Null
        $seen = 0
        while (-not (Test-Path $done)) {
            Start-Sleep 3
            if (Test-Path $log) { $all = Get-Content $log; $all | Select-Object -Skip $seen; $seen = $all.Count }
        }
        if (Test-Path $log) { Get-Content $log | Select-Object -Skip $seen }
    } finally { schtasks /delete /tn $task /f 2>$null | Out-Null }   # una tarea de un solo disparo ya puede haberse borrado sola
    exit [int](Get-Content $done)
}

try {
    if ($InSession) { Start-Transcript $log -Force | Out-Null }
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:PATH = "$JavaHome\bin;$env:PATH" }
    if ($WixDir) { $env:PATH = "$WixDir;$env:PATH" }
    if (-not (Get-Command light.exe -ErrorAction SilentlyContinue)) { throw 'No encuentro WiX (light.exe): instálalo o usa -WixDir.' }
    .\build.ps1
    if ($LASTEXITCODE -ne 0) { throw "build.ps1 terminó con código $LASTEXITCODE" }
    if (-not (Get-ChildItem dist\*.msi -ErrorAction SilentlyContinue)) { throw 'No se generó ningún .msi en dist\' }
    $code = 0
} catch {
    Write-Host "ERROR: $_"
    $code = 1
} finally {
    if ($InSession) { Stop-Transcript | Out-Null; Set-Content $done $code }
}
exit $code
