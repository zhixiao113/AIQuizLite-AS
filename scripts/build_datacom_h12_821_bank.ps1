param(
    [string]$Source = 'E:\数通hcip\Datacom-IP(H12-821)题库.docx',
    [string]$OutputDirectory = 'E:\projects\project-bs\AIQuizLite-AS\artifacts\datacom-h12-821-bank'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-ZipXml([System.IO.Compression.ZipArchive]$archive, [string]$entryName) {
    $entry = $archive.GetEntry($entryName)
    if ($null -eq $entry) { throw "DOCX 缺少 $entryName" }
    $stream = $entry.Open()
    try {
        $xml = [System.Xml.XmlDocument]::new()
        $xml.Load($stream)
        return $xml
    } finally {
        $stream.Dispose()
    }
}

function Check-Question([System.Collections.IDictionary]$question) {
    if ($null -eq $question) { return }
    $number = [int]$question['numberInPaper']
    if ([string]::IsNullOrWhiteSpace($question['stem'])) { throw "第 $number 题题干为空" }
    if ($null -eq $question['answers'] -or $question['answers'].Count -eq 0) {
        throw "第 $number 题缺少答案"
    }
    $options = @($question['options'].ToArray())
    if ($question['type'] -in @('single', 'multi')) {
        if ($options.Count -ne 4) { throw "第 $number 题选项数不是 4" }
        $keys = @($options | ForEach-Object { $_.key })
        foreach ($answer in $question['answers']) {
            if ($answer -notin $keys) { throw "第 $number 题答案 $answer 不在选项中" }
        }
        if ($question['type'] -eq 'single' -and $question['answers'].Count -ne 1) {
            throw "第 $number 题单选答案数异常"
        }
        if ($question['type'] -eq 'multi' -and $question['answers'].Count -lt 2) {
            throw "第 $number 题多选答案数异常"
        }
    } elseif ($question['type'] -eq 'judge') {
        if ($options.Count -ne 2 -or $question['answers'][0] -notin @('A', 'B')) {
            throw "第 $number 题判断题格式异常：选项 $($options.Count)，答案 $($question['answers'] -join ',')"
        }
    } elseif ($options.Count -ne 0) {
        throw "第 $number 题填空题不应有选项"
    }
    $question['options'] = $options
}

$bankName = 'Datacom-IP(H12-821)题库'
$expectedImages = @(5, 10, 12, 24, 35, 38, 59, 60, 61, 95)
$typeNames = @{
    '单选题' = 'single'
    '多选题' = 'multi'
    '判断题' = 'judge'
    '填空题' = 'fill_blank'
}
$sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($Source)
try {
    $document = Read-ZipXml $sourceArchive 'word/document.xml'
    $relationships = Read-ZipXml $sourceArchive 'word/_rels/document.xml.rels'
    $mediaByRelationship = @{}
    foreach ($relationship in $relationships.DocumentElement.ChildNodes) {
        $mediaByRelationship[$relationship.GetAttribute('Id')] = $relationship.GetAttribute('Target')
    }
    $namespaces = [System.Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespaces.AddNamespace('w', 'http://schemas.openxmlformats.org/wordprocessingml/2006/main')
    $namespaces.AddNamespace('a', 'http://schemas.openxmlformats.org/drawingml/2006/main')
    $relationshipNamespace = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    $paragraphs = $document.SelectNodes('//w:body/w:p', $namespaces)

    $questions = [System.Collections.Generic.List[object]]::new()
    $images = [ordered]@{}
    $current = $null
    for ($paragraphIndex = 0; $paragraphIndex -lt $paragraphs.Count; $paragraphIndex++) {
        $paragraph = $paragraphs[$paragraphIndex]
        $line = $paragraph.InnerText.Trim()
        $heading = [regex]::Match($line, '^(?<number>\d+)[.、]（(?<kind>单选题|多选题|判断题|填空题)）(?<stem>.*)$')
        if ($heading.Success) {
            if ($null -ne $current) {
                Check-Question $current
                $questions.Add($current)
            }
            $number = [int]$heading.Groups['number'].Value
            $current = [ordered]@{
                id = "q$number"
                index = $number
                paper = $bankName
                numberInPaper = $number
                type = $typeNames[$heading.Groups['kind'].Value]
                stem = $heading.Groups['stem'].Value.Trim()
                options = [System.Collections.Generic.List[object]]::new()
                answers = $null
                explanation = $null
                image = $null
            }
        } elseif ($null -ne $current -and $line -ne '' -and $line -notmatch '^[一二三四五六]、') {
            $option = [regex]::Match($line, '^(?<key>[A-H])[、.．:：]\s*(?<text>.+)$')
            $answer = [regex]::Match($line, '^(?:【)?(?:正确答案|答案)(?:】|：|:)\s*(?<value>.*)$')
            $explanation = [regex]::Match($line, '^(?:【)?解析(?:】|：|:)\s*(?<value>.*)$')
            if ($option.Success) {
                $current['options'].Add([ordered]@{
                    key = $option.Groups['key'].Value
                    text = $option.Groups['text'].Value.Trim()
                })
            } elseif ($answer.Success) {
                $rawAnswer = $answer.Groups['value'].Value.Trim()
                switch ($current['type']) {
                    'single' {
                        if ($rawAnswer -notmatch '^[A-H]$') { throw "第 $($current['numberInPaper']) 题单选答案格式异常" }
                        $current['answers'] = @($rawAnswer)
                    }
                    'multi' {
                        if ($rawAnswer -notmatch '^[A-H]{2,}$') { throw "第 $($current['numberInPaper']) 题多选答案格式异常" }
                        $current['answers'] = @($rawAnswer.ToCharArray() | ForEach-Object { [string]$_ })
                    }
                    'judge' {
                        if ($rawAnswer -notin @('正确', '错误')) { throw "第 $($current['numberInPaper']) 题判断答案格式异常" }
                        $current['answers'] = @(if ($rawAnswer -eq '正确') { 'A' } else { 'B' })
                    }
                    'fill_blank' {
                        if ($rawAnswer -eq '') { throw "第 $($current['numberInPaper']) 题填空答案为空" }
                        $current['answers'] = @($rawAnswer)
                    }
                }
            } elseif ($explanation.Success) {
                $current['explanation'] = $explanation.Groups['value'].Value.Trim()
            } else {
                throw "第 $($current['numberInPaper']) 题附近存在未识别文本：段落 $paragraphIndex $line"
            }
        }
        foreach ($blip in $paragraph.SelectNodes('.//a:blip', $namespaces)) {
            if ($null -eq $current) { throw "段落 $paragraphIndex 的图片没有题号" }
            $relationshipId = $blip.GetAttribute('embed', $relationshipNamespace)
            $mediaTarget = $mediaByRelationship[$relationshipId]
            if ($mediaTarget -notmatch '^media/image\d+\.png$') {
                throw "第 $($current['numberInPaper']) 题图片格式或关系异常：$mediaTarget"
            }
            if ($null -ne $current['image']) { throw "第 $($current['numberInPaper']) 题有多张图片" }
            $outputImage = 'image/q{0}.png' -f $current['numberInPaper']
            $current['image'] = $outputImage
            $images[$outputImage] = 'word/' + $mediaTarget
        }
    }
    Check-Question $current
    $questions.Add($current)

    if ($questions.Count -ne 100) { throw "题量异常：$($questions.Count)，预期 100" }
    for ($i = 0; $i -lt $questions.Count; $i++) {
        if ($questions[$i]['numberInPaper'] -ne $i + 1) { throw "题号缺失或重复：位置 $i" }
    }
    $actualImages = @($images.Keys | Sort-Object)
    $expectedImagePaths = @($expectedImages | ForEach-Object { "image/q$_.png" } | Sort-Object)
    if ($actualImages.Count -ne $expectedImagePaths.Count -or
            (@(Compare-Object $actualImages $expectedImagePaths).Count -ne 0)) {
        throw "图片题号与请求不符：$($actualImages -join ', ')"
    }
    $typeCounts = $questions | Group-Object { $_['type'] }
    foreach ($expected in @(@('single', 38), @('multi', 28), @('judge', 28), @('fill_blank', 6))) {
        $actual = @($typeCounts | Where-Object Name -eq $expected[0])[0].Count
        if ($actual -ne $expected[1]) { throw "$($expected[0]) 题量异常：$actual" }
    }

    $bank = [ordered]@{ name = $bankName; questions = $questions.ToArray() }
    $json = ConvertTo-Json -InputObject $bank -Depth 12
    $null = $json | ConvertFrom-Json
    if (!(Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
    }
    $outputPath = Join-Path $OutputDirectory "$bankName.zip"
    if (Test-Path -LiteralPath $outputPath) { throw "输出已存在，不覆盖：$outputPath" }
    $buffer = [System.IO.MemoryStream]::new()
    try {
        $outputArchive = [System.IO.Compression.ZipArchive]::new(
            $buffer, [System.IO.Compression.ZipArchiveMode]::Create, $true,
            [System.Text.Encoding]::UTF8)
        try {
            $jsonEntry = $outputArchive.CreateEntry("$bankName.json", [System.IO.Compression.CompressionLevel]::Optimal)
            $writer = [System.IO.StreamWriter]::new($jsonEntry.Open(), [System.Text.UTF8Encoding]::new($false))
            try { $writer.Write($json) } finally { $writer.Dispose() }
            $null = $outputArchive.CreateEntry('image/')
            foreach ($imagePath in $images.Keys) {
                $sourceImage = $sourceArchive.GetEntry($images[$imagePath])
                if ($null -eq $sourceImage) { throw "源图片不存在：$($images[$imagePath])" }
                $outputImage = $outputArchive.CreateEntry($imagePath, [System.IO.Compression.CompressionLevel]::Optimal)
                $inputStream = $sourceImage.Open()
                $outputStream = $outputImage.Open()
                try { $inputStream.CopyTo($outputStream) }
                finally { $inputStream.Dispose(); $outputStream.Dispose() }
            }
        } finally {
            $outputArchive.Dispose()
        }
        [System.IO.File]::WriteAllBytes($outputPath, $buffer.ToArray())
    } finally {
        $buffer.Dispose()
    }
    "Created $outputPath"
    "Questions: $($questions.Count); images: $($images.Count); bytes: $((Get-Item -LiteralPath $outputPath).Length)"
} finally {
    $sourceArchive.Dispose()
}
