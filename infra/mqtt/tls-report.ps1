# Rapport TLS du broker en ligne avec testssl.sh (étape 4) : protocoles, suites de chiffrement,
# certificat, failles connues. Rapport HTML + JSON dans infra\mqtt\reports\. Demande Docker Desktop.
#
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1 -Target mqtt.exemple.me:8883
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\tls-report.ps1 -Ip 13.62.97.65   # DNS pas encore propagé
#
# --add-ca : testssl fait confiance à la CA du projet (sinon « chaîne de confiance invalide »).
# Toujours viser le NOM du broker (celui du certificat), jamais l'IP seule : avec -Target IP:8883,
# testssl signale « certificate does not match supplied URI ». -Ip garde le nom et force l'IP.
param([string]$Target = '', [string]$Ip = '')
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
if ($Target -match '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') {
    throw "Cible = une IP : le certificat porte le nom du broker. Utilise -Target mqtt.<domaine>:8883 (et -Ip $($Target.Split(':')[0]) si le DNS n'est pas prêt)."
}
$IpArgs = @()
if (-not [string]::IsNullOrEmpty($Ip)) { $IpArgs = @('--ip', $Ip) }
$CaDir = Join-Path $Here 'certs-serveur'
if (-not (Test-Path (Join-Path $CaDir 'ca.pem'))) { $CaDir = Join-Path $Here 'certs' }
if (-not (Test-Path (Join-Path $CaDir 'ca.pem'))) { throw 'Certificat de la CA introuvable : lance deploy\fetch-ca.ps1' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker introuvable : lance Docker Desktop' }
$Out = Join-Path $Here 'reports'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

Write-Host "Rapport TLS de $Target (CA : $CaDir\ca.pem)"
& docker run --rm -t -v "$($Out):/data" -v "$($CaDir):/certs:ro" --workdir /data `
    ghcr.io/testssl/testssl.sh:3.2 --add-ca /certs/ca.pem @IpArgs --htmlfile /data/ --jsonfile /data/ $Target
if ($LASTEXITCODE -ne 0) { Write-Host "testssl a signalé des points à regarder (code $LASTEXITCODE) : voir le rapport." }
Write-Host ''
Write-Host "Rapports dans $Out :"
Get-ChildItem $Out | Sort-Object LastWriteTime -Descending | Select-Object -First 2 | ForEach-Object { Write-Host "  $($_.Name)" }
