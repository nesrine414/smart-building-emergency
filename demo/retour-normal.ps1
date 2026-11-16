# Retour au calme : valeurs normales dans les trois salles (l'admin clôt ensuite l'incident).
# Double-cliquer sur retour-normal.cmd (Windows), ou depuis PowerShell :
#     .\demo\retour-normal.ps1              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     .\demo\retour-normal.ps1 -Local       # vers le broker de ce PC, pour répéter
#     .\demo\retour-normal.ps1 -ViaServeur  # plan B : lancé sur le serveur par SSH
param([switch]$Local, [switch]$ViaServeur, [int]$Count = 30)
. (Join-Path $PSScriptRoot "_commun.ps1")
Start-Scenario -Scenario 'normal' -Rooms '201,101,102' -Count $Count -Interval 1 -Local:$Local -ViaServeur:$ViaServeur
