<#
.SYNOPSIS
    Export the signing keystore as a single-line base64 string.

.DESCRIPTION
    Produces the value for SIGNING_KEYSTORE_BASE64, which the CI pipeline
    needs to sign release APKs. The result is copied to the clipboard and
    also saved to keystore\keystore.b64.txt (a gitignored path).

    SECURITY: this base64 string IS your signing key (minus the password).
    Never paste it into chat, issues, commits or any public place.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\export-keystore-base64.ps1
#>

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$ksPath = Join-Path $root "keystore\safeline-client.jks"
if (-not (Test-Path $ksPath)) {
    Write-Host "[X] keystore not found: keystore\safeline-client.jks"
    Write-Host "    Run scripts\generate-signing.ps1 first."
    exit 1
}

$b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($ksPath))

$outFile = Join-Path $root "keystore\keystore.b64.txt"
[System.IO.File]::WriteAllText($outFile, $b64, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "[OK] base64 exported"
Write-Host "    length: $($b64.Length) chars"
Write-Host "    saved:  keystore\keystore.b64.txt  (gitignored)"
Write-Host ""

try {
    Set-Clipboard -Value $b64
    Write-Host "    also copied to clipboard - paste it into SIGNING_KEYSTORE_BASE64"
} catch {
    Write-Host "    (clipboard unavailable; open the file above and copy manually)"
}

Write-Host ""
Write-Host "IMPORTANT: treat this string like your private key. Never commit it."
