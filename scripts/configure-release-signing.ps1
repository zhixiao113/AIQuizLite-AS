# Interactive, local-only enrollment. DPAPI binds ciphertext to this Windows user on this PC.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $projectRoot '.local-signing'
$storePassword = Read-Host 'Keystore password' -AsSecureString
$samePassword = Read-Host 'Use same password for key0? [Y/n]'
$keyPassword = if ($samePassword -match '^[nN]$') {
    Read-Host 'key0 password' -AsSecureString
} else { $storePassword }
try {
    $data = @{ storePasswordDpapi = ConvertFrom-SecureString $storePassword
               keyPasswordDpapi = ConvertFrom-SecureString $keyPassword }
    # Setup utility intentionally writes only encrypted local configuration, never source files.
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $data | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory 'release-passwords.json') -Encoding UTF8
    Write-Output 'Local encrypted signing configuration saved. No password is printed or stored in plaintext.'
} finally {
    if ($keyPassword -ne $storePassword) { $keyPassword.Dispose() }
    $storePassword.Dispose()
}
