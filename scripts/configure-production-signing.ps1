[CmdletBinding()]
param([switch]$Authorized)
$ErrorActionPreference = 'Stop'
if (!$Authorized) { throw 'Explicit permission to upload original credentials into production Secrets is required.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $projectRoot '.local-signing/release-passwords.json'
$keystoreFile = [IO.Path]::GetFullPath((Join-Path $projectRoot '../app_sign.jks'))
function Convert-DpapiToPlainText([string]$encrypted) {
    $secure = ConvertTo-SecureString $encrypted
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
$names = @('GITHUB_TOKEN', 'AIQUIZ_CLOUD_SIGNING_AUTHORIZED', 'AIQUIZ_RELEASE_STORE_FILE',
    'AIQUIZ_RELEASE_STORE_PASSWORD', 'AIQUIZ_RELEASE_KEY_PASSWORD', 'PYTHONIOENCODING')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location $projectRoot
try {
    $credentialText = "protocol=https`nhost=github.com`n`n" | git credential fill
    foreach ($line in $credentialText) {
        if ($line.StartsWith('password=')) { $env:GITHUB_TOKEN = $line.Substring(9) }
    }
    if (!$env:GITHUB_TOKEN) { throw 'Existing GitHub authentication unavailable.' }
    $credentials = Get-Content -LiteralPath $credentialFile -Raw | ConvertFrom-Json
    $env:AIQUIZ_CLOUD_SIGNING_AUTHORIZED = 'true'
    $env:AIQUIZ_RELEASE_STORE_FILE = $keystoreFile
    $env:AIQUIZ_RELEASE_STORE_PASSWORD = Convert-DpapiToPlainText $credentials.storePasswordDpapi
    $env:AIQUIZ_RELEASE_KEY_PASSWORD = Convert-DpapiToPlainText $credentials.keyPasswordDpapi
    $env:PYTHONIOENCODING = 'utf-8'
    & python scripts/configure_production_signing.py
    if ($LASTEXITCODE -ne 0) { throw 'Production signing setup was not verified; no values logged.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
    $credentials = $null; $credentialText = $null; $line = $null
    Pop-Location
}
