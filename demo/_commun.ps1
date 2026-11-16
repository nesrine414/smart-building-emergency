# Fonctions communes des scripts de démonstration (étape 4, personne 1). Ne pas lancer seul :
# les scripts incendie.ps1, fuite-gaz.ps1 et retour-normal.ps1 le chargent.
#
# Trois façons de jouer un scénario :
#   (défaut)     depuis ce PC vers le broker en ligne MQTT_BROKER_PUBLIC_HOST:8883, CA du serveur
#   -Local       vers le broker de ce PC (localhost:8883, CA locale) : pour répéter sans le serveur
#   -ViaServeur  plan B : le simulateur tourne SUR le serveur, lancé par SSH (port 22), utile si le
#                réseau de la salle bloque le port 8883
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $Root '.env'
$Simulator = Join-Path $Root 'iot/simulator/simulator.py'

function Get-EnvValue([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value) -and (Test-Path $EnvFile)) {
        foreach ($line in Get-Content -Path $EnvFile -Encoding UTF8) {
            if ($line -match "^\s*$Name=(.*)$") { $value = $Matches[1].Trim().Trim('"').Trim("'") }
        }
    }
    return $value
}

function Stop-Demo([string]$Message) {
    Write-Host ''
    Write-Host "ERREUR : $Message" -ForegroundColor Red
    exit 1
}

function Find-Python {
    # Windows PowerShell 5.1 transforme la sortie d'erreur d'un programme en exception quand
    # $ErrorActionPreference vaut Stop : on la relâche le temps de ce test.
    $ErrorActionPreference = 'Continue'
    foreach ($candidate in @(@('python'), @('py', '-3'), @('python3'))) {
        $exe = $candidate[0]
        if (Get-Command $exe -ErrorAction SilentlyContinue) {
            $extra = @($candidate | Select-Object -Skip 1)
            & $exe @extra -c 'import paho.mqtt' 2>$null
            if ($LASTEXITCODE -eq 0) { return , $candidate }
        }
    }
    Stop-Demo "Python avec paho-mqtt introuvable. Installe-le : pip install -r iot\simulator\requirements.txt"
}

function Start-Scenario {
    param(
        [Parameter(Mandatory = $true)][string]$Scenario,
        [Parameter(Mandatory = $true)][string]$Rooms,
        [int]$Count = 120,
        [double]$Interval = 1,
        [switch]$Local,
        [switch]$ViaServeur
    )
    if (-not (Test-Path $EnvFile)) { Stop-Demo "Fichier .env absent ($EnvFile) : copie le .env de l'équipe." }
    if ([string]::IsNullOrEmpty((Get-EnvValue 'MQTT_DEVICE_PASSWORD'))) { Stop-Demo 'MQTT_DEVICE_PASSWORD vide dans .env' }
    $interval = $Interval.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $common = @('--tls', '--rooms', $Rooms, '--scenario', $Scenario, '--interval', $interval, '--count', "$Count")

    if ($ViaServeur) {
        $target = Get-EnvValue 'SBE_SSH_TARGET'
        $key = Get-EnvValue 'SBE_SSH_KEY'
        if ([string]::IsNullOrEmpty($target)) { Stop-Demo 'SBE_SSH_TARGET vide dans .env (ex. sbe@13.38.0.1)' }
        if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { Stop-Demo 'ssh introuvable (client OpenSSH de Windows)' }
        $remote = "cd /opt/sbe/smart-building-emergency && /opt/sbe/venv/bin/python iot/simulator/simulator.py --host localhost " + ($common -join ' ')
        Write-Host "Plan B : scénario '$Scenario' lancé sur le serveur ($target), salles $Rooms" -ForegroundColor Cyan
        $sshArgs = @('-t')
        if (-not [string]::IsNullOrEmpty($key)) { $sshArgs += @('-i', $key) }
        & ssh @sshArgs $target $remote
        if ($LASTEXITCODE -ne 0) { Stop-Demo "le scénario sur le serveur s'est arrêté avec le code $LASTEXITCODE" }
        return
    }

    if ($Local) {
        $mqttHost = 'localhost'
        $caFile = Join-Path $Root 'infra/mqtt/certs/ca.pem'
        $caHint = 'génère les certificats locaux : infra\mqtt\gen-certs.ps1'
    } else {
        $mqttHost = Get-EnvValue 'MQTT_BROKER_PUBLIC_HOST'
        if ([string]::IsNullOrEmpty($mqttHost)) { Stop-Demo 'MQTT_BROKER_PUBLIC_HOST vide dans .env (ex. mqtt.sbe-supcom.me)' }
        $caFile = Join-Path $Root 'infra/mqtt/certs-serveur/ca.pem'
        $caHint = 'récupère-le avec deploy\fetch-ca.ps1'
    }
    if (-not (Test-Path $caFile)) { Stop-Demo "certificat de la CA absent : $caFile ($caHint)" }

    $python = Find-Python
    $exe = $python[0]
    $pyArgs = @($python | Select-Object -Skip 1) + @($Simulator, '--host', $mqttHost, '--cafile', $caFile) + $common
    Write-Host "Scénario '$Scenario' vers $($mqttHost):8883, salles $Rooms, $Count cycles de $interval s (Ctrl+C pour arrêter)" -ForegroundColor Cyan
    & $exe @pyArgs
    if ($LASTEXITCODE -ne 0) {
        Stop-Demo "le simulateur s'est arrêté avec le code $LASTEXITCODE. Si le port 8883 est bloqué sur ce réseau, lance le plan B (fichier ...-par-serveur.cmd)."
    }
    Write-Host 'Scénario terminé.' -ForegroundColor Green
}
