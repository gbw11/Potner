# Deploy current HEAD to the Raspberry Pi over git (not scp).
# The Pi checks out the same branch, so a direct push is refused —
# push to a temp ref, then fast-forward merge on the Pi.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\deploy-to-pi.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\deploy-to-pi.ps1 -Once
#
# Requires: SSH key registered (scripts\setup-pi-ssh.ps1), clean committed HEAD.
# The collector service is NOT restarted here (sudo needs a password) — run
#   sudo systemctl restart sensor-collector
# on the Pi afterwards so the new code/config takes effect.
param(
    [string]$RemoteDir = "/home/e104/S15P11E104",
    [switch]$Once
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

Write-Host "Checking SSH..."
ssh -o BatchMode=yes -o ConnectTimeout=8 raspberrypi "echo connected" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "SSH failed. Run scripts\setup-pi-ssh.ps1 first (enter Pi password once)."
}

git diff --quiet; $dirty = $LASTEXITCODE -ne 0
git diff --cached --quiet; $staged = $LASTEXITCODE -ne 0
if ($dirty -or $staged) {
    Write-Error "Working tree is not clean. Commit first — only committed HEAD is deployed."
}

Write-Host "Pushing HEAD to Pi temp ref..."
git push "ssh://raspberrypi$RemoteDir" HEAD:refs/heads/sync-tmp
if ($LASTEXITCODE -ne 0) { Write-Error "git push failed." }

Write-Host "Fast-forwarding on Pi..."
ssh raspberrypi "cd $RemoteDir && git merge --ff-only sync-tmp && git branch -D sync-tmp"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Fast-forward failed. Pi has diverged — inspect with: ssh raspberrypi 'cd $RemoteDir && git status'"
}

if ($Once) {
    Write-Host "Running one collection cycle on Pi..."
    ssh raspberrypi "cd $RemoteDir && unset DEVICE_ID && .venv/bin/python main.py --config config/raspberry_pi.yaml --once"
}

Write-Host "Deploy done. Restart the service on the Pi to apply:"
Write-Host "  ssh raspberrypi   ->   sudo systemctl restart sensor-collector"
