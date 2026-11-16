# Incendie dans la salle 201 : la salle passe au rouge, sirène et porte de secours, score RISK qui monte.
# Double-cliquer sur incendie.cmd (Windows), ou depuis PowerShell :
#     .\demo\incendie.ps1              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     .\demo\incendie.ps1 -Local       # vers le broker de ce PC, pour répéter
#     .\demo\incendie.ps1 -ViaServeur  # plan B : lancé sur le serveur par SSH
param([switch]$Local, [switch]$ViaServeur, [int]$Count = 120)
. (Join-Path $PSScriptRoot "_commun.ps1")
Start-Scenario -Scenario 'fire' -Rooms '201,101,102' -Count $Count -Interval 1 -Local:$Local -ViaServeur:$ViaServeur
