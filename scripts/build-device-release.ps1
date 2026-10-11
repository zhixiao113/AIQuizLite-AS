[CmdletBinding()]
param(
    [string]$Notes = '优化连线操作、保留题目来源信息，新增按题型专项练习。'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$credentialFile = Join-Path $projectRoot '.local-signing/release-passwords.json'
$keystoreFile = [IO.Path]::GetFullPath((Join-Path $projectRoot '../app_sign.jks'))
$expectedSigner = '6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8'
$gradleFile = Join-Path $projectRoot 'app/build.gradle'
$source = Get-Content -LiteralPath $gradleFile -Raw
$codeMatch = [regex]::Match($source, 'versionCode\s+(\d+)')
$nameMatch = [regex]::Match($source, 'versionName\s+"([^"]+)"')
if (!$codeMatch.Success -or !$nameMatch.Success) { throw 'Cannot read application version.' }
$versionCode = [int]$codeMatch.Groups[1].Value
$versionName = $nameMatch.Groups[1].Value
$publishedManifest = Join-Path $projectRoot 'update-server/version.json'
if (Test-Path -LiteralPath $publishedManifest) {
    $published = Get-Content -LiteralPath $publishedManifest -Raw | ConvertFrom-Json
    if ($versionCode -le [int]$published.versionCode) {
        throw 'Bump versionCode and versionName before preparing a NEW device acceptance release.'
    }
}
if (!(Test-Path -LiteralPath $keystoreFile)) { throw 'Original app_sign.jks is missing.' }
if (!(Test-Path -LiteralPath $credentialFile)) {
    throw 'Local signing credentials are missing. Run scripts/configure-release-signing.ps1.'
}

function Convert-DpapiToPlainText([string]$encrypted) {
    $secure = ConvertTo-SecureString $encrypted
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}

# Never print credentials or pass them on command lines. Clear this process's environment afterwards.
$environmentNames = @('AIQUIZ_RELEASE_STORE_FILE', 'AIQUIZ_RELEASE_KEY_ALIAS',
    'AIQUIZ_RELEASE_STORE_PASSWORD', 'AIQUIZ_RELEASE_KEY_PASSWORD', 'AIQUIZ_BUILD_DIR', 'PYTHONIOENCODING')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location $projectRoot
try {
    $credentials = Get-Content -LiteralPath $credentialFile -Raw | ConvertFrom-Json
    $env:AIQUIZ_RELEASE_STORE_FILE = $keystoreFile
    $env:AIQUIZ_RELEASE_KEY_ALIAS = 'key0'
    $env:AIQUIZ_RELEASE_STORE_PASSWORD = Convert-DpapiToPlainText $credentials.storePasswordDpapi
    $env:AIQUIZ_RELEASE_KEY_PASSWORD = Convert-DpapiToPlainText $credentials.keyPasswordDpapi
    $env:AIQUIZ_BUILD_DIR = Join-Path $projectRoot "artifacts/device-release-v$versionCode"
    $env:PYTHONIOENCODING = 'utf-8'

    $publicCertificate = & keytool -exportcert -rfc -keystore $keystoreFile -alias key0 `
        -storepass:env AIQUIZ_RELEASE_STORE_PASSWORD 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot open original keystore/alias. Check store password.' }
    $pem = $publicCertificate -join "`n"
    $encoded = [regex]::Match($pem, '(?s)-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----')
    if (!$encoded.Success) { throw 'Cannot read signing certificate.' }
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = [BitConverter]::ToString($hash.ComputeHash(
                [Convert]::FromBase64String($encoded.Groups[1].Value))).Replace('-', '').ToLowerInvariant()
    } finally { $hash.Dispose() }
    if ($digest -ne $expectedSigner) { throw 'Original signing certificate differs from distributed app. Refusing build.' }
    Write-Output "Preparing signed device release $versionName (code $versionCode), certificate verified."

    & python -m unittest discover -s tests -p test_update_server.py
    if ($LASTEXITCODE -ne 0) { throw 'Update publication regression tests failed.' }
    & .\gradlew.bat :app:lintRelease :app:testReleaseUnitTest :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android release quality gates failed. Existing update manifest remains unchanged.' }
    $apk = Join-Path $env:AIQUIZ_BUILD_DIR 'outputs/apk/release/app-release.apk'
    # Publisher checks APK version/package/signature again before atomic manifest replacement.
    & python update_server.py $Notes --apk $apk --prepare-only
    if ($LASTEXITCODE -ne 0) { throw 'APK verification/publication failed. Do not distribute this artifact.' }
    Write-Output "READY: $apk"
    Write-Output 'Prepared update-server/version.json and signed APK. This script does not start the server or FRP tunnel.'
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name]) }
    $credentials = $null
    Pop-Location
}
