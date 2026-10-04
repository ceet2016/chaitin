<#
.SYNOPSIS
    Generate Android signing keystore and write keystore.properties

.DESCRIPTION
    Reads config from keystore\signing.env, calls keytool to create a PKCS12
    keystore, then generates keystore.properties for Gradle.

    Both the keystore and keystore.properties live in gitignored paths,
    so they never enter the repository.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\generate-signing.ps1

.EXAMPLE
    $env:JDK_BIN="D:\jdk17"; powershell -ExecutionPolicy Bypass -File scripts\generate-signing.ps1

.NOTES
    This file is ASCII-only on purpose: Windows PowerShell 5.1 reads scripts
    without a BOM using the system ANSI codepage, which corrupts non-ASCII text.
#>

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---------- Read config ----------
$envFile = Join-Path $root "keystore\signing.env"
if (-not (Test-Path $envFile)) {
    Write-Host "[X] not found: keystore\signing.env"
    exit 1
}

$cfg = @{}
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $idx = $line.IndexOf("=")
    if ($idx -lt 1) { return }
    $cfg[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
}

$required = @(
    "DNAME_CN", "DNAME_OU", "DNAME_O", "DNAME_L", "DNAME_ST", "DNAME_C",
    "KEY_ALIAS", "STORE_PASSWORD", "KEY_PASSWORD", "VALIDITY_DAYS",
    "KEYSTORE_FILE", "PROPS_FILE"
)
foreach ($k in $required) {
    if (-not $cfg.ContainsKey($k) -or $cfg[$k] -eq "") {
        Write-Host "[X] missing value in signing.env: $k"
        exit 1
    }
}

if ($cfg["STORE_PASSWORD"] -like "*TODO*" -or $cfg["KEY_PASSWORD"] -like "*TODO*" -or
    $cfg["STORE_PASSWORD"] -eq "" -or $cfg["KEY_PASSWORD"] -eq "") {
    Write-Host ""
    Write-Host "[X] Please set STORE_PASSWORD and KEY_PASSWORD in keystore\signing.env first."
    exit 1
}

# ---------- Locate keytool ----------
$jdk = if ($env:JDK_BIN) { $env:JDK_BIN } else { "C:\Users\Administrator\.workbuddy\binaries\android\jdk17tmp\jdk-17.0.20.1+1" }
$keytool = Join-Path $jdk "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    Write-Host "[X] keytool not found: $keytool"
    Write-Host "    Set JDK_BIN to your JDK root, e.g.:"
    Write-Host '    $env:JDK_BIN="D:\jdk17"; powershell -ExecutionPolicy Bypass -File scripts\generate-signing.ps1'
    exit 1
}

# ---------- Overwrite protection ----------
$ksPath = Join-Path $root $cfg["KEYSTORE_FILE"]
if (Test-Path $ksPath) {
    Write-Host ""
    Write-Host "[!] $($cfg['KEYSTORE_FILE']) already exists."
    Write-Host "    Overwriting breaks in-place upgrades for apps signed with the old key."
    $ans = Read-Host "    type yes to overwrite"
    if ($ans -ne "yes") {
        Write-Host "cancelled"
        exit 0
    }
}

$ksDir = Split-Path -Parent $ksPath
if (-not (Test-Path $ksDir)) { New-Item -ItemType Directory -Path $ksDir -Force | Out-Null }

$cfgDname = "CN=$($cfg['DNAME_CN']), OU=$($cfg['DNAME_OU']), O=$($cfg['DNAME_O']), L=$($cfg['DNAME_L']), ST=$($cfg['DNAME_ST']), C=$($cfg['DNAME_C'])"

Write-Host ""
Write-Host "==> Generating keystore: $($cfg['KEYSTORE_FILE'])"
Write-Host "    alias: $($cfg['KEY_ALIAS'])   validity: $($cfg['VALIDITY_DAYS']) days"

& $keytool -genkeypair -v `
    -keystore $ksPath `
    -storetype PKCS12 `
    -keyalg RSA -keysize 2048 `
    -validity $cfg["VALIDITY_DAYS"] `
    -alias $cfg["KEY_ALIAS"] `
    -storepass $cfg["STORE_PASSWORD"] `
    -keypass $cfg["KEY_PASSWORD"] `
    -dname $cfgDname

if ($LASTEXITCODE -ne 0) {
    Write-Host "[X] keytool failed with exit code $LASTEXITCODE"
    exit 1
}

# ---------- Write keystore.properties (UTF-8, no BOM) ----------
$propsPath = Join-Path $root $cfg["PROPS_FILE"]
$propsText = "storeFile=$($cfg['KEYSTORE_FILE'])`n" +
             "storePassword=$($cfg['STORE_PASSWORD'])`n" +
             "keyAlias=$($cfg['KEY_ALIAS'])`n" +
             "keyPassword=$($cfg['KEY_PASSWORD'])"

[System.IO.File]::WriteAllText($propsPath, $propsText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "[OK] done."
Write-Host "    keystore: $($cfg['KEYSTORE_FILE'])"
Write-Host "    config:   $($cfg['PROPS_FILE'])  (gitignored)"
Write-Host ""
Write-Host "Next:"
Write-Host "    .\gradlew assembleRelease"
Write-Host "    apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk"
Write-Host "    your own DN = success / CN=Android Debug = not signed"
Write-Host ""
Write-Host "REMINDER: back up the keystore file and both passwords offline."
Write-Host "          Losing them means you can never publish updates again."
