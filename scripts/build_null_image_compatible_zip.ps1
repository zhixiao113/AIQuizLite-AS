param(
    [Parameter(Mandatory = $true)][string]$SourceZip,
    [Parameter(Mandatory = $true)][string]$OutputZip
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.Drawing

$sourcePath = (Resolve-Path -LiteralPath $SourceZip).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputZip)
if (Test-Path -LiteralPath $outputPath) { throw "输出文件已存在：$outputPath" }

$source = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
try {
    $jsonEntries = @($source.Entries | Where-Object {
        $_.FullName -match '^[^/]+\.json$'
    })
    if ($jsonEntries.Count -ne 1) { throw '源 ZIP 必须恰好包含一个根目录 JSON 文件' }

    $blankEntryName = 'image/__transparent_placeholder.png'
    if ($source.GetEntry($blankEntryName)) { throw "源 ZIP 已包含 $blankEntryName" }
    foreach ($entry in $source.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        if ($entry.FullName -ne $jsonEntries[0].FullName -and
                $entry.FullName -notmatch '^image/[^/]+\.(png|jpe?g|webp)$') {
            throw "源 ZIP 包含意外文件：$($entry.FullName)"
        }
    }

    $reader = [System.IO.StreamReader]::new($jsonEntries[0].Open(), [System.Text.Encoding]::UTF8)
    try { $questions = ConvertFrom-Json -InputObject $reader.ReadToEnd() }
    finally { $reader.Dispose() }
    if ($questions -isnot [array] -or $questions.Count -eq 0) {
        throw '源 JSON 必须是非空题目数组'
    }

    $replaced = 0
    foreach ($question in $questions) {
        if ($null -eq $question.image) {
            $question.image = $blankEntryName
            $replaced++
        }
    }
    if ($replaced -eq 0) { throw '源 JSON 没有空图片字段，无需生成兼容包' }
    $jsonBytes = [System.Text.UTF8Encoding]::new($false).GetBytes(
        (ConvertTo-Json -InputObject $questions -Depth 100))

    $outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    $outputStream = [System.IO.File]::Open($outputPath, [System.IO.FileMode]::CreateNew)
    try {
        $output = [System.IO.Compression.ZipArchive]::new(
            $outputStream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            foreach ($entry in $source.Entries) {
                if ($entry.FullName.EndsWith('/')) { continue }
                $created = $output.CreateEntry($entry.FullName,
                    [System.IO.Compression.CompressionLevel]::Optimal)
                $target = $created.Open()
                try {
                    if ($entry.FullName -eq $jsonEntries[0].FullName) {
                        $target.Write($jsonBytes, 0, $jsonBytes.Length)
                    } else {
                        $input = $entry.Open()
                        try { $input.CopyTo($target) }
                        finally { $input.Dispose() }
                    }
                } finally { $target.Dispose() }
            }

            $blank = [System.Drawing.Bitmap]::new(1, 1,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                $blank.SetPixel(0, 0, [System.Drawing.Color]::Transparent)
                $target = $output.CreateEntry($blankEntryName,
                    [System.IO.Compression.CompressionLevel]::Optimal).Open()
                try { $blank.Save($target, [System.Drawing.Imaging.ImageFormat]::Png) }
                finally { $target.Dispose() }
            } finally { $blank.Dispose() }
        } finally { $output.Dispose() }
    } finally { $outputStream.Dispose() }

    Write-Output "生成 $outputPath；题目 $($questions.Count) 道，透明占位图引用 $replaced 处。"
} finally { $source.Dispose() }
