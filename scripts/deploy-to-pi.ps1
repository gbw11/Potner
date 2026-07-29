# Sync this project to the Raspberry Pi and optionally run once.
param(
    [string]$RemoteDir = "~/sensor-collector",
    [switch]$Once,
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

Write-Host "Checking SSH..."
ssh -o BatchMode=yes -o ConnectTimeout=8 raspberrypi "echo connected" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "SSH failed. Run scripts\setup-pi-ssh.ps1 first (enter Pi password once)."
}

Write-Host "Creating remote directory: $RemoteDir"
ssh raspberrypi "mkdir -p $RemoteDir"

Write-Host "Uploading project files..."
scp -r `
    main.py `
    plant.py `
    capture_photo.py `
    config.yaml `
    config.raspberry_pi.yaml `
    requirements.txt `
    src `
    "raspberrypi:${RemoteDir}/"

if ($Install) {
    Write-Host "Installing Python deps on Pi..."
    # Use ; not newlines so Windows CRLF does not break remote bash
    # Pi 5 Blinka needs system lgpio (apt: python3-lgpio) via --system-site-packages
    ssh raspberrypi "cd $RemoteDir; python3 -m venv --system-site-packages .venv; . .venv/bin/activate; pip install -U pip; pip install -r requirements.txt; pip install adafruit-circuitpython-dht Adafruit-Blinka || true; sudo apt-get install -y python3-picamera2 || true"
}

if ($Once) {
    Write-Host "Running one collection cycle on Pi..."
    ssh raspberrypi "cd $RemoteDir; . .venv/bin/activate; python main.py --config config.raspberry_pi.yaml --once"
} else {
    Write-Host "Deploy done. To run on Pi:"
    Write-Host "  ssh raspberrypi"
    Write-Host "  cd $RemoteDir && source .venv/bin/activate && python main.py --config config.raspberry_pi.yaml --once"
}
