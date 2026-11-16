# Prépare les variables d'environnement du backend WildFly.
# À lancer avec un point devant :
# . .\infra\mqtt\backend-env.ps1

$Here = $PSScriptRoot
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$EnvFile = Join-Path $Root '.env'

if (-not (Test-Path $EnvFile)) {
    throw "Fichier .env introuvable : $EnvFile"
}

# Lecture du fichier .env
$values = @{}
foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}

# Chargement VAPID AVANT le contrôle du truststore
if ([string]::IsNullOrWhiteSpace($values['VAPID_PUBLIC_KEY']) -or
    [string]::IsNullOrWhiteSpace($values['VAPID_PRIVATE_KEY'])) {
    throw "Clés VAPID absentes du fichier .env."
}

$env:VAPID_PUBLIC_KEY = $values['VAPID_PUBLIC_KEY']
$env:VAPID_PRIVATE_KEY = $values['VAPID_PRIVATE_KEY']

if ($values['VAPID_SUBJECT']) {
    $env:VAPID_SUBJECT = $values['VAPID_SUBJECT']
}
else {
    $env:VAPID_SUBJECT = 'mailto:admin@smartbuilding.com'
}

# Configuration MQTT
$truststore = Join-Path $Here 'certs\truststore.jks'

if (-not (Test-Path $truststore)) {
    throw "Truststore introuvable ($truststore) : lance d'abord infra\mqtt\gen-certs.ps1"
}

$env:MQTT_BROKER_URL = 'ssl://mqtt.smartbuilding.local:8883'
$env:MQTT_USERNAME = 'sbe-backend'
$env:MQTT_PASSWORD = $values['MQTT_BACKEND_PASSWORD']
$env:MQTT_TRUSTSTORE = (Resolve-Path $truststore).Path
$env:MQTT_TRUSTSTORE_PASSWORD = $values['MQTT_TRUSTSTORE_PASSWORD']

# JWT facultatif
if ($values['JWT_SECRET']) {
    $env:JWT_SECRET = $values['JWT_SECRET']
}

Write-Host "Variables VAPID chargées." -ForegroundColor Green
Write-Host "Variables MQTT prêtes pour WildFly dans cette console :"
Write-Host "  MQTT_BROKER_URL = $env:MQTT_BROKER_URL"
Write-Host "  MQTT_USERNAME   = $env:MQTT_USERNAME"
Write-Host "  MQTT_TRUSTSTORE = $env:MQTT_TRUSTSTORE"
Write-Host "  (mots de passe et clés non affichés)"