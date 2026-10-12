[CmdletBinding()]
param(
    [ValidateSet('publish', 'bootstrap', 'rollback')][string]$Mode = 'publish',
    [string]$Notes = '新增 GitHub 应用内在线更新，保留本地备用与发布回滚。',
    [int]$TargetCode = 0
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousToken = [Environment]::GetEnvironmentVariable('GITHUB_TOKEN')
$previousEncoding = [Environment]::GetEnvironmentVariable('PYTHONIOENCODING')
$previousPrompt = [Environment]::GetEnvironmentVariable('GIT_TERMINAL_PROMPT')
Push-Location $projectRoot
try {
    $env:PYTHONIOENCODING = 'utf-8'
    $env:GIT_TERMINAL_PROMPT = '0'
    if (!$env:GITHUB_TOKEN) {
        $credentialText = "protocol=https`nhost=github.com`n`n" | git credential fill
        if ($LASTEXITCODE -ne 0) { throw 'Existing GitHub login is unavailable; authenticate Git first.' }
        foreach ($line in $credentialText) {
            if ($line.StartsWith('password=')) { $env:GITHUB_TOKEN = $line.Substring(9) }
        }
        if (!$env:GITHUB_TOKEN) { throw 'Git credential manager did not provide authentication.' }
    }
    if ($Mode -eq 'publish') {
        $commit = (git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read source commit.' }
        $version = [regex]::Match((Get-Content app/build.gradle -Raw), 'versionCode\s+(\d+)').Groups[1].Value
        if (!$version) { throw 'Cannot read source version.' }
        # Publishing a dirty or untracked source would falsely attribute the APK to HEAD.
        $sourceChanges = @(git status --porcelain -- app/src app/build.gradle)
        if ($sourceChanges.Count -gt 0) { throw 'Commit application source/version before publishing; no release was uploaded.' }
        $remoteMain = @(git ls-remote origin refs/heads/main)
        if ($LASTEXITCODE -ne 0 -or $remoteMain.Count -ne 1 -or ($remoteMain[0] -split '\s+')[0] -ne $commit) {
            throw 'Publish only after this committed source is the current remote main; no release was uploaded.'
        }
        $apk = Join-Path $projectRoot "artifacts/device-release-v$version/outputs/apk/release/app-release.apk"
        if (!(Test-Path -LiteralPath $apk)) { throw 'Run the original-signer device release helper first.' }
        & python scripts/github_release.py publish --apk $apk --commit $commit --notes $Notes
    } elseif ($Mode -eq 'rollback' -and $TargetCode -gt 0) {
        & python scripts/github_release.py rollback --target-code $TargetCode
    } else {
        & python scripts/github_release.py $Mode
    }
    if ($LASTEXITCODE -ne 0) { throw 'GitHub publication/recovery was not verified; inspect artifacts/github-release/report.json.' }
} finally {
    [Environment]::SetEnvironmentVariable('GITHUB_TOKEN', $previousToken)
    [Environment]::SetEnvironmentVariable('PYTHONIOENCODING', $previousEncoding)
    [Environment]::SetEnvironmentVariable('GIT_TERMINAL_PROMPT', $previousPrompt)
    $credentialText = $null
    Pop-Location
}
