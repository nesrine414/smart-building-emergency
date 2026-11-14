# Prépare les variables d'environnement MQTT du backend (WildFly) dans la console PowerShell en cours.
# À lancer avec un point devant (dot-source), dans la console qui démarrera WildFly :
#
#     . .\infra\mqtt\backend-env.ps1
#     & "$env:WILDFLY_HOME\bin\standalone.bat"        (ou mvn wildfly:run, etc.)
#
# Les valeurs viennent du fichier .env à la racine du dépôt. Rien n'est écrit sur le disque.
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$EnvFile = Join-Path $Root '.env'
if (-not (Test-Path $EnvFile)) { throw "Fichier .env introuvable ($EnvFile) : lance d'abord python infra\mqtt\gen_credentials.py --init-env" }

$values = @{}
foreach ($line in Get-Content -Path $EnvFile -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'") }
}
$truststore = Join-Path $Here 'certs\truststore.jks'
if (-not (Test-Path $truststore)) { throw "Truststore introuvable ($truststore) : lance d'abord infra\mqtt\gen-certs.ps1" }

$env:MQTT_BROKER_URL = 'ssl://localhost:8883'
$env:MQTT_USERNAME = 'sbe-backend'
$env:MQTT_PASSWORD = $values['MQTT_BACKEND_PASSWORD']
$env:MQTT_TRUSTSTORE = (Resolve-Path $truststore).Path
$env:MQTT_TRUSTSTORE_PASSWORD = $values['MQTT_TRUSTSTORE_PASSWORD']
if ($values['JWT_SECRET']) { $env:JWT_SECRET = $values['JWT_SECRET'] }   # utilisé par la personne 2

Write-Host "Variables MQTT prêtes pour WildFly dans cette console :"
Write-Host "  MQTT_BROKER_URL  = $env:MQTT_BROKER_URL"
Write-Host "  MQTT_USERNAME    = $env:MQTT_USERNAME"
Write-Host "  MQTT_TRUSTSTORE  = $env:MQTT_TRUSTSTORE"
Write-Host "  (mots de passe chargés, non affichés)"
