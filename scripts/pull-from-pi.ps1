# Pull sensor-collector from Raspberry Pi into this PC project folder (overwrite).
param(
    [string]$RemoteDir = "~/sensor-collector"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot

Write-Host "Checking SSH..."
ssh -o BatchMode=yes -o ConnectTimeout=8 raspberrypi "echo connected" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "SSH failed. Run scripts\setup-pi-ssh.ps1 first."
}

Write-Host "Pulling from raspberrypi:${RemoteDir} -> $ProjectRoot"
Write-Host "(overwrites local files; skips .venv / data / .git)"

# Core project files/dirs (same set we deploy)
$items = @(
    "main.py",
    "requirements.txt",
    "README.md",
    "Jenkinsfile",
    "pytest.ini",
    ".gitignore",
    "cli",
    "config",
    "src",
    "scripts",
    "systemd",
    "tests"
)

foreach ($item in $items) {
    $remote = "raspberrypi:${RemoteDir}/$item"
    # Check exists on Pi
    ssh raspberrypi "test -e $RemoteDir/$item" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "skip (missing on Pi): $item"
        continue
    }
    Write-Host "pull $item"
    scp -r $remote "$ProjectRoot/"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "scp failed for $item"
    }
}

Write-Host "Done. Review with: git status"
