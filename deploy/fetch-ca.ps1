# Récupère le certificat de la CA du serveur en ligne, pour vérifier mqtt.<domaine> depuis ce PC.
# Utilise SBE_SSH_TARGET (ex. sbe@13.38.0.1) et SBE_SSH_KEY (clé .pem) du .env.
#     powershell -ExecutionPolicy Bypass -File deploy\fetch-ca.ps1
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
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

$Target = Get-EnvValue 'SBE_SSH_TARGET'
$Key = Get-EnvValue 'SBE_SSH_KEY'
if ([string]::IsNullOrEmpty($Target)) { throw "SBE_SSH_TARGET vide dans .env (ex. sbe@13.38.0.1)" }
if (-not (Get-Command scp -ErrorAction SilentlyContinue)) {
    throw "scp introuvable : active le client OpenSSH de Windows (Paramètres > Applications > Fonctionnalités facultatives)"
}
$Out = Join-Path $Root 'infra\mqtt\certs-serveur'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Destination = Join-Path $Out 'ca.pem'
$Arguments = @()
if (-not [string]::IsNullOrEmpty($Key)) { $Arguments += @('-i', $Key) }
$Arguments += @("$($Target):/opt/sbe/smart-building-emergency/infra/mqtt/certs/ca.pem", $Destination)
& scp @Arguments
if ($LASTEXITCODE -ne 0) { throw "scp a échoué (clé, IP ou groupe de sécurité : le port 22 est-il ouvert pour ton IP ?)" }

$Certificate = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList $Destination
$Sha256 = [System.Security.Cryptography.SHA256]::Create()
$Fingerprint = ($Sha256.ComputeHash($Certificate.RawData) | ForEach-Object { $_.ToString('X2') }) -join ':'
Write-Host "Copié : $Destination"
Write-Host "Sujet : $($Certificate.Subject)"
Write-Host "Empreinte SHA-256 (à comparer avec celle affichée sur le serveur) :"
Write-Host "  $Fingerprint"
