# Fuite de gaz dans la salle 101 : ventilation ouverte et vanne de gaz fermée, puis le gaz redescend.
# Double-cliquer sur fuite-gaz.cmd (Windows), ou depuis PowerShell :
#     .\demo\fuite-gaz.ps1              # vers le broker en ligne (MQTT_BROKER_PUBLIC_HOST)
#     .\demo\fuite-gaz.ps1 -Local       # vers le broker de ce PC, pour répéter
#     .\demo\fuite-gaz.ps1 -ViaServeur  # plan B : lancé sur le serveur par SSH
param([switch]$Local, [switch]$ViaServeur, [int]$Count = 150)
. (Join-Path $PSScriptRoot "_commun.ps1")
Start-Scenario -Scenario 'gas-leak' -Rooms '101,102,201' -Count $Count -Interval 1 -Local:$Local -ViaServeur:$ViaServeur
