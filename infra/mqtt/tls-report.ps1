# Rapport TLS du broker en ligne avec testssl.sh (étape 4) : protocoles, suites de chiffrement,
# certificat, failles connues. Rapport HTML + JSON dans infra\mqtt\reports\. Demande Docker Desktop.
#
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1 -Target mqtt.exemple.me:8883
#
# --add-ca : testssl fait confiance à la CA du projet (sinon « chaîne de confiance invalide »).
param([string]$Target = '')
$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$EnvFile = Join-Path $Root '.env'

function Get-EnvValue([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value) -and (Test-Path $EnvFile)) {
        foreach ($line in Get-Content -Path $EnvFile -Encoding UTF8) {
            if ($line -match "^\s*$Name=(.*)$") { $value = $Matches[1].Trim().Trim('"').Trim("'") }
        }
    }
    return $value
}

if ([string]::IsNullOrEmpty($Target)) {
    $PublicHost = Get-EnvValue 'MQTT_BROKER_PUBLIC_HOST'
    if ([string]::IsNullOrEmpty($PublicHost)) { throw 'Indique -Target hôte:port, ou MQTT_BROKER_PUBLIC_HOST dans .env' }
    $Target = "$($PublicHost):8883"
}
$CaDir = Join-Path $Here 'certs-serveur'
if (-not (Test-Path (Join-Path $CaDir 'ca.pem'))) { $CaDir = Join-Path $Here 'certs' }
if (-not (Test-Path (Join-Path $CaDir 'ca.pem'))) { throw 'Certificat de la CA introuvable : lance deploy\fetch-ca.ps1' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker introuvable : lance Docker Desktop' }
$Out = Join-Path $Here 'reports'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

Write-Host "Rapport TLS de $Target (CA : $CaDir\ca.pem)"
& docker run --rm -t -v "$($Out):/data" -v "$($CaDir):/certs:ro" --workdir /data `
    ghcr.io/testssl/testssl.sh:3.2 --add-ca /certs/ca.pem --htmlfile /data/ --jsonfile /data/ $Target
if ($LASTEXITCODE -ne 0) { Write-Host "testssl a signalé des points à regarder (code $LASTEXITCODE) : voir le rapport." }
Write-Host ''
Write-Host "Rapports dans $Out :"
Get-ChildItem $Out | Sort-Object LastWriteTime -Descending | Select-Object -First 2 | ForEach-Object { Write-Host "  $($_.Name)" }
