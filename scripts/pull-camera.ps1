# Pull Pi camera files into the single local folder: data/camera
param(
    [string]$RemoteDir = "~/S15P11E104",
    [string]$LocalDir = "data/camera"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null

Write-Host "Fetching raspberrypi:$RemoteDir/data/camera -> $LocalDir"
scp "raspberrypi:${RemoteDir}/data/camera/." "$LocalDir/"

Write-Host "Done. Open $LocalDir"
Get-ChildItem $LocalDir | Sort-Object LastWriteTime -Descending | Select-Object -First 20 Name, Length, LastWriteTime
