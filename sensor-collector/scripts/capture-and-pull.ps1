# Capture on Pi and sync into the single folder data/camera
# Prefer: python capture_photo.py  (same behavior from PC)
param(
    [string]$RemoteDir = "~/sensor-collector",
    [string]$LocalDir = "data/camera"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null

Write-Host "1) Capturing on Pi..."
ssh raspberrypi "cd $RemoteDir && . .venv/bin/activate && python capture_photo.py --local-only"

Write-Host "2) Syncing to $LocalDir ..."
scp "raspberrypi:${RemoteDir}/data/camera/." "$LocalDir/"

Write-Host "Done."
Get-ChildItem $LocalDir | Sort-Object LastWriteTime -Descending | Select-Object -First 10 Name, Length, LastWriteTime
