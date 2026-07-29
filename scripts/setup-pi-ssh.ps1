# One-time: copy this PC's SSH public key to the Raspberry Pi.
# You will be asked for the Pi user password once.
$ErrorActionPreference = "Stop"

$pubKey = Join-Path $env:USERPROFILE ".ssh\id_ed25519.pub"
if (-not (Test-Path $pubKey)) {
    Write-Error "Public key not found: $pubKey"
}

Write-Host "Registering SSH key on raspberrypi (e104@192.168.30.91)..."
Write-Host "Enter the Pi password when prompted."
Get-Content $pubKey | ssh raspberrypi "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo KEY_OK"

Write-Host ""
Write-Host "Testing passwordless SSH..."
ssh -o BatchMode=yes raspberrypi "echo OK; hostname; whoami"
