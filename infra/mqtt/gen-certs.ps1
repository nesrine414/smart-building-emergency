# Génère les certificats TLS du broker MQTT (étape 3, contrat 5) avec keytool seulement.
#
#   certs\ca.jks          CA locale du projet (clé privée de la CA : à garder pour re-signer)
#   certs\ca.pem          certificat de la CA, pour les clients Python (--cafile) et l'ESP32
#   certs\broker.jks      clé et certificat du broker HiveMQ (signé par la CA), monté dans le conteneur
#   certs\truststore.jks  certificat de la CA, pour le backend Java (MQTT_TRUSTSTORE)
#
# Le certificat du broker est valable pour localhost, 127.0.0.1, hivemq (nom du service Docker),
# mqtt.smartbuilding.local et, si MQTT_BROKER_LAN_IP est défini dans .env, l'IP du PC sur le Wi-Fi.
#
# Utilisation, depuis la racine du dépôt (PowerShell) :
#     powershell -ExecutionPolicy Bypass -File infra\mqtt\gen-certs.ps1 [-Force]
param([switch]$Force)
$ErrorActionPreference = 'Stop'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent (Split-Path -Parent $Here)
$Out = Join-Path $Here 'certs'
$EnvFile = if ($env:ENV_FILE) { $env:ENV_FILE } else { Join-Path $Root '.env' }

# Lit une variable : environnement d'abord, puis .env
function Get-EnvValue([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value) -and (Test-Path $EnvFile)) {
        foreach ($line in Get-Content -Path $EnvFile -Encoding UTF8) {
            if ($line -match "^\s*$Name=(.*)$") { $value = $Matches[1].Trim().Trim('"').Trim("'") }
        }
    }
    return $value
}

# Lance keytool et s'arrête au premier échec (keytool écrit ses avertissements sur stderr :
# on ne regarde que le code de retour).
function Invoke-Keytool([string[]]$Arguments) {
    & $script:Keytool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "keytool a échoué : keytool $($Arguments[0])" }
}

$KeystorePassword = Get-EnvValue 'MQTT_KEYSTORE_PASSWORD'
$TruststorePassword = Get-EnvValue 'MQTT_TRUSTSTORE_PASSWORD'
$LanIp = Get-EnvValue 'MQTT_BROKER_LAN_IP'

if ([string]::IsNullOrEmpty($KeystorePassword) -or $KeystorePassword.Length -lt 6 -or
    [string]::IsNullOrEmpty($TruststorePassword) -or $TruststorePassword.Length -lt 6) {
    Write-Error "MQTT_KEYSTORE_PASSWORD et MQTT_TRUSTSTORE_PASSWORD doivent faire au moins 6 caractères (.env). Astuce : python infra\mqtt\gen_credentials.py --init-env"
}

# keytool : PATH, sinon JAVA_HOME
$command = Get-Command keytool -ErrorAction SilentlyContinue
if ($command) {
    $Keytool = $command.Path
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
    $Keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} else {
    Write-Error "keytool introuvable : installe un JDK (17 ou plus) et ajoute son dossier bin au PATH, ou définis JAVA_HOME."
}

if ((Test-Path (Join-Path $Out 'broker.jks')) -and -not $Force) {
    Write-Error "$Out\broker.jks existe déjà. Relance avec -Force pour tout régénérer."
}
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null
Push-Location $Out
try {
    $San = 'dns:localhost,ip:127.0.0.1,dns:hivemq,dns:mqtt.smartbuilding.local'
    if (-not [string]::IsNullOrEmpty($LanIp)) { $San = "$San,ip:$LanIp" }

    Write-Host '1/5 CA locale du projet'
    Invoke-Keytool @('-genkeypair', '-alias', 'sbe-ca', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
        '-dname', 'CN=Smart Building Emergency Local CA, O=SupCom, C=TN',
        '-ext', 'bc:c=ca:true,pathlen:0', '-ext', 'ku:c=keyCertSign,cRLSign',
        '-keystore', 'ca.jks', '-storetype', 'JKS', '-storepass', $KeystorePassword, '-keypass', $KeystorePassword)
    Invoke-Keytool @('-exportcert', '-rfc', '-alias', 'sbe-ca', '-keystore', 'ca.jks',
        '-storepass', $KeystorePassword, '-file', 'ca.pem')

    Write-Host '2/5 Clé du broker'
    Invoke-Keytool @('-genkeypair', '-alias', 'broker', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '825',
        '-dname', 'CN=localhost, O=SupCom, C=TN',
        '-keystore', 'broker.jks', '-storetype', 'JKS', '-storepass', $KeystorePassword, '-keypass', $KeystorePassword)

    Write-Host "3/5 Signature du certificat du broker par la CA ($San)"
    Invoke-Keytool @('-certreq', '-alias', 'broker', '-keystore', 'broker.jks',
        '-storepass', $KeystorePassword, '-file', 'broker.csr')
    Invoke-Keytool @('-gencert', '-alias', 'sbe-ca', '-keystore', 'ca.jks', '-storepass', $KeystorePassword,
        '-infile', 'broker.csr', '-outfile', 'broker.pem', '-rfc', '-validity', '825',
        '-ext', "san=$San", '-ext', 'ku:c=digitalSignature,keyEncipherment', '-ext', 'eku=serverAuth')

    Write-Host '4/5 Chaîne complète dans broker.jks'
    Invoke-Keytool @('-importcert', '-noprompt', '-alias', 'sbe-ca', '-file', 'ca.pem',
        '-keystore', 'broker.jks', '-storepass', $KeystorePassword)
    Invoke-Keytool @('-importcert', '-noprompt', '-alias', 'broker', '-file', 'broker.pem',
        '-keystore', 'broker.jks', '-storepass', $KeystorePassword)

    Write-Host '5/5 Truststore des clients Java'
    Invoke-Keytool @('-importcert', '-noprompt', '-alias', 'sbe-ca', '-file', 'ca.pem',
        '-keystore', 'truststore.jks', '-storetype', 'JKS', '-storepass', $TruststorePassword)

    Remove-Item 'broker.csr' -Force
} finally {
    Pop-Location
}

Write-Host ''
Write-Host "Certificats prêts dans $Out :"
Get-ChildItem $Out | ForEach-Object { Write-Host "  $($_.Name)" }
