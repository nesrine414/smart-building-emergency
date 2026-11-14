# Télécharge et installe l'extension HiveMQ File RBAC (authentification + droits par topic).
# HiveMQ Community Edition n'a pas de mot de passe intégré : sans cette extension, le broker
# accepte tout le monde (extension "allow-all") ou personne.
#
# Résultat : infra\mqtt\extensions\hivemq-file-rbac-extension\ (monté dans le conteneur HiveMQ).
# Utilisation, depuis la racine du dépôt (PowerShell) :
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\install-rbac.ps1
$ErrorActionPreference = 'Stop'

$Version = '4.6.19'
# Empreinte SHA-256 de l'archive officielle 4.6.19 (vérifiée au téléchargement)
$Sha256 = '088ef038b0550099cde4d0ac829595c1d53ed0f68635e673b45750ec7036cb4f'
$Url = "https://github.com/hivemq/hivemq-file-rbac-extension/releases/download/$Version/hivemq-file-rbac-extension-$Version.zip"

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dest = Join-Path $Here 'extensions'
$Target = Join-Path $Dest 'hivemq-file-rbac-extension'

if (Test-Path (Join-Path $Target "hivemq-file-rbac-extension-$Version.jar")) {
    Write-Host "Extension File RBAC $Version déjà installée dans $Target"
    exit 0
}

$Zip = Join-Path ([System.IO.Path]::GetTempPath()) "hivemq-file-rbac-$Version.zip"
Write-Host "Téléchargement de $Url"
$ProgressPreference = 'SilentlyContinue'   # sinon Windows PowerShell 5.1 télécharge très lentement
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -Uri $Url -OutFile $Zip -UseBasicParsing

$Actual = (Get-FileHash -Path $Zip -Algorithm SHA256).Hash.ToLower()
if ($Actual -ne $Sha256) {
    Remove-Item $Zip -Force
    throw "Empreinte SHA-256 inattendue ($Actual) : archive refusée."
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
if (Test-Path $Target) { Remove-Item $Target -Recurse -Force }
Expand-Archive -Path $Zip -DestinationPath $Dest -Force
Remove-Item $Zip -Force
# Les fichiers d'exemple de l'archive (user1/pass1...) sont remplacés par les nôtres au montage
# Docker ; on les supprime pour qu'ils ne servent jamais par erreur.
$Example = Join-Path $Target 'conf\credentials.xml'
if (Test-Path $Example) { Remove-Item $Example -Force }
Write-Host "Extension installée : $Target"
