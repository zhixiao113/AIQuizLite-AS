param(
    [string]$Source = 'E:\数通hcip\Datacom-IP_H12-821_03_试卷.docx',
    [string]$OutputDirectory = 'E:\projects\project-bs\AIQuizLite-AS\artifacts\datacom-h12-821-03-bank'
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
    } finally { $stream.Dispose() }
}

function Finalize-Question([System.Collections.IDictionary]$question) {
    if ($null -eq $question) { return }
    $number = [int]$question['numberInPaper']
    if ([string]::IsNullOrWhiteSpace($question['stem'])) { throw "第 $number 题题干为空" }
    if (-not $question['_hasAnswer']) { throw "第 $number 题缺少正确答案" }
    if ($question['_explanationParts'].Count -gt 0) {
        $question['explanation'] = $question['_explanationParts'] -join "`n"
    }
    if ($question['type'] -eq 'matching') {
        $left = @($question['_left'].ToArray())
        $right = @($question['_right'].ToArray())
        $pairs = @($question['_pairs'].ToArray())
        if ($left.Count -eq 0 -or $right.Count -eq 0 -or $pairs.Count -ne $right.Count) {
            throw "第 $number 题连线数量异常：左 $($left.Count)，右 $($right.Count)，关系 $($pairs.Count)"
        }
        $leftIds = @($left | ForEach-Object { $_.id })
        $rightIds = @($right | ForEach-Object { $_.id })
        $usedRight = @($pairs | ForEach-Object { $_.rightId })
        $usedLeft = @($pairs | ForEach-Object { $_.leftId })
        foreach ($pair in $pairs) {
            if ($pair.leftId -notin $leftIds -or $pair.rightId -notin $rightIds) {
                throw "第 $number 题的标准连线引用了不存在的项目"
            }
        }
        if (@($usedRight | Sort-Object -Unique).Count -ne $right.Count) {
            throw "第 $number 题右侧项目没有一一覆盖"
        }
        $mode = if (@($usedLeft | Sort-Object -Unique).Count -lt $pairs.Count) {
            'left_reusable'
        } else { 'one_to_one' }
        $question['matching'] = [ordered]@{
            mode = $mode
            leftItems = $left
            rightItems = $right
            correctPairs = $pairs
        }
        $question['options'] = @()
        $question['answers'] = @()
    } else {
        $options = @($question['_optionList'].ToArray())
        $answers = @($question['answers'])
        if ($answers.Count -eq 0) { throw "第 $number 题答案为空" }
        if ($question['type'] -in @('single', 'multi')) {
            if ($options.Count -ne 4) { throw "第 $number 题选项数不是 4" }
            $keys = @($options | ForEach-Object { $_.key })
            foreach ($answer in $answers) {
                if ($answer -notin $keys) { throw "第 $number 题答案 $answer 不在选项中" }
            }
            if ($question['type'] -eq 'single' -and $answers.Count -ne 1) {
                throw "第 $number 题单选答案数异常"
            }
            if ($question['type'] -eq 'multi' -and $answers.Count -lt 2) {
                throw "第 $number 题多选答案数异常"
            }
        } elseif ($question['type'] -eq 'judge') {
            if ($options.Count -ne 2 -or $answers.Count -ne 1 -or $answers[0] -notin @('A', 'B')) {
                throw "第 $number 题判断题格式异常"
            }
        } elseif ($options.Count -ne 0) {
            throw "第 $number 题填空题不应有选项"
        }
        $question['options'] = $options
        $question['answers'] = $answers
    }
    $question['explanationImages'] = @($question['_explanationImageList'].ToArray())
    foreach ($key in @('_optionList', '_left', '_right', '_pairs', '_hasAnswer',
                    '_explanationParts', '_explanationImageList', '_side')) {
        $question.Remove($key)
    }
}

$bankName = 'Datacom-IP(H12-821)03卷题库'
$jsonName = 'Datacom-IP(H12-821)题库.json'
$typeNames = @{
    '单选题' = 'single'
    '多选题' = 'multi'
    '判断题' = 'judge'
    '填空题' = 'fill_blank'
    '连线题' = 'matching'
}

# The source places Q89's answer diagram immediately after the stem, so role must
# be determined from its content, not only its paragraph position.
$imagePlan = @{
    'image1.png' = @(5, 'prompt', 'image/q5.png')
    'image2.png' = @(9, 'prompt', 'image/q9.png')
    'image3.png' = @(15, 'prompt', 'image/q15.png')
    'image4.png' = @(16, 'prompt', 'image/q16.png')
    'image5.png' = @(21, 'prompt', 'image/q21.png')
    'image6.png' = @(22, 'prompt', 'image/q22.png')
    'image7.png' = @(31, 'prompt', 'image/q31.png')
    'image8.png' = @(37, 'prompt', 'image/q37.png')
    'image9.png' = @(39, 'prompt', 'image/q39.png')
    'image10.png' = @(49, 'prompt', 'image/q49.png')
    'image11.png' = @(86, 'prompt', 'image/q86.png')
    'image12.png' = @(89, 'explanation', 'image/q89_answer.png')
    'image13.png' = @(90, 'prompt', 'image/q90.png')
    'image14.png' = @(90, 'explanation', 'image/q90_answer.png')
    'image15.png' = @(91, 'explanation', 'image/q91_answer.png')
    'image16.png' = @(92, 'explanation', 'image/q92_answer.png')
    'image17.png' = @(93, 'explanation', 'image/q93_answer.png')
    'image18.png' = @(94, 'explanation', 'image/q94_answer.png')
    'image19.png' = @(95, 'explanation', 'image/q95_answer.png')
    'image20.png' = @(96, 'explanation', 'image/q96_answer_1.png')
    'image21.png' = @(96, 'explanation', 'image/q96_answer_2.png')
    'image22.png' = @(97, 'explanation', 'image/q97_answer.png')
    'image23.jpg' = @(98, 'prompt', 'image/q98.jpg')
    'image24.png' = @(99, 'explanation', 'image/q99_answer.png')
    'image25.png' = @(100, 'prompt', 'image/q100.png')
    'image26.png' = @(101, 'explanation', 'image/q101_answer.png')
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
    $packagedImages = [ordered]@{}
    $seenImages = [System.Collections.Generic.HashSet[string]]::new()
    $current = $null
    for ($paragraphIndex = 0; $paragraphIndex -lt $paragraphs.Count; $paragraphIndex++) {
        $paragraph = $paragraphs[$paragraphIndex]
        $line = $paragraph.InnerText.Trim()
        $heading = [regex]::Match($line, '^(?<number>\d+)[.、]（(?<kind>单选题|多选题|判断题|填空题|连线题)）(?<stem>.*)$')
        if ($heading.Success) {
            if ($null -ne $current) {
                Finalize-Question $current
                $questions.Add($current)
            }
            $number = [int]$heading.Groups['number'].Value
            $current = [ordered]@{
                id = "q$number"
                index = $number
                paper = $bankName
                sourceName = 'Datacom-IP_H12-821_03_试卷.docx'
                numberInPaper = $number
                type = $typeNames[$heading.Groups['kind'].Value]
                stem = $heading.Groups['stem'].Value.Trim()
                options = @()
                answers = @()
                explanation = $null
                image = $null
                explanationImages = @()
                _optionList = [System.Collections.Generic.List[object]]::new()
                _left = [System.Collections.Generic.List[object]]::new()
                _right = [System.Collections.Generic.List[object]]::new()
                _pairs = [System.Collections.Generic.List[object]]::new()
                _hasAnswer = $false
                _explanationParts = [System.Collections.Generic.List[string]]::new()
                _explanationImageList = [System.Collections.Generic.List[string]]::new()
                _side = ''
            }
            if ($current['type'] -eq 'judge') {
                $current['_optionList'].Add([ordered]@{ key = 'A'; text = '正确' })
                $current['_optionList'].Add([ordered]@{ key = 'B'; text = '错误' })
            }
        } elseif ($null -ne $current -and $line -ne '' -and $line -notmatch '^[一二三四五六]、') {
            $option = [regex]::Match($line, '^(?<key>[A-H])[、.．:：]\s*(?<text>.+)$')
            $answer = [regex]::Match($line, '^【正确答案】\s*(?<value>.*)$')
            $explanation = [regex]::Match($line, '^【(?:解析|答案对应)】\s*(?<value>.*)$')
            if ($current['type'] -eq 'matching' -and $line -eq '【左侧】') {
                $current['_side'] = 'left'
            } elseif ($current['type'] -eq 'matching' -and $line -eq '【右侧】') {
                $current['_side'] = 'right'
            } elseif ($current['type'] -eq 'matching' -and $line -eq '【连线解析图】') {
                $current['_side'] = ''
            } elseif ($current['type'] -eq 'matching' -and $line -match '^(?<item>\d+)、(?<text>.+)$') {
                $itemNumber = [int]$Matches['item']
                $itemText = $Matches['text'].Trim()
                if ($current['_side'] -eq 'left') {
                    $current['_left'].Add([ordered]@{ id = "L$itemNumber"; text = $itemText })
                } elseif ($current['_side'] -eq 'right') {
                    $current['_right'].Add([ordered]@{ id = "R$itemNumber"; text = $itemText })
                } else { throw "第 $($current['numberInPaper']) 题连线项目无左右分组" }
            } elseif ($option.Success -and $current['type'] -in @('single', 'multi')) {
                $current['_optionList'].Add([ordered]@{
                    key = $option.Groups['key'].Value
                    text = $option.Groups['text'].Value.Trim()
                })
            } elseif ($answer.Success) {
                if ($current['_hasAnswer']) { throw "第 $($current['numberInPaper']) 题出现重复答案" }
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
                    'matching' {
                        foreach ($token in ($rawAnswer -split '\s*[,，]\s*')) {
                            if ($token -notmatch '^(\d+)\s*-\s*(\d+)$') {
                                throw "第 $($current['numberInPaper']) 题连线答案格式异常：$token"
                            }
                            $current['_pairs'].Add([ordered]@{
                                leftId = 'L' + $Matches[1]
                                rightId = 'R' + $Matches[2]
                            })
                        }
                    }
                }
                $current['_hasAnswer'] = $true
                $current['_side'] = ''
            } elseif ($explanation.Success) {
                $value = $explanation.Groups['value'].Value.Trim()
                if ($value -ne '') { $current['_explanationParts'].Add($value) }
                $current['_side'] = ''
            } elseif ($current['type'] -in @('single', 'multi') -and $current['_optionList'].Count -eq 0) {
                # The source has command snippets in Q24 and Q44 before the options.
                $current['stem'] += "`n" + $line
            } else {
                throw "第 $($current['numberInPaper']) 题附近存在未识别文本：段落 $paragraphIndex $line"
            }
        }

        foreach ($blip in $paragraph.SelectNodes('.//a:blip', $namespaces)) {
            if ($null -eq $current) { throw "段落 $paragraphIndex 的图片没有题号" }
            $relationshipId = $blip.GetAttribute('embed', $relationshipNamespace)
            $mediaTarget = $mediaByRelationship[$relationshipId]
            $sourceImageName = [System.IO.Path]::GetFileName($mediaTarget)
            $plan = $imagePlan[$sourceImageName]
            if ($null -eq $plan -or $plan[0] -ne $current['numberInPaper']) {
                throw "第 $($current['numberInPaper']) 题图片位置异常：$mediaTarget"
            }
            if (!$seenImages.Add($sourceImageName)) { throw "重复图片：$sourceImageName" }
            $outputImage = [string]$plan[2]
            if ($plan[1] -eq 'prompt') {
                if ($null -ne $current['image']) { throw "第 $($current['numberInPaper']) 题有多张作答图" }
                $current['image'] = $outputImage
            } else {
                $current['_explanationImageList'].Add($outputImage)
            }
            $packagedImages[$outputImage] = 'word/' + $mediaTarget
        }
    }
    Finalize-Question $current
    $questions.Add($current)

    if ($questions.Count -ne 101) { throw "题量异常：$($questions.Count)，预期 101" }
    for ($i = 0; $i -lt $questions.Count; $i++) {
        if ($questions[$i]['numberInPaper'] -ne $i + 1) { throw "题号缺失或重复：位置 $i" }
    }
    if ($seenImages.Count -ne 26 -or $packagedImages.Count -ne 26) {
        throw "图片数量异常：识别 $($seenImages.Count)，打包 $($packagedImages.Count)"
    }
    $typeCounts = $questions | Group-Object { $_['type'] }
    foreach ($expected in @(@('single', 28), @('multi', 26), @('judge', 28),
                           @('fill_blank', 6), @('matching', 13))) {
        $actual = @($typeCounts | Where-Object Name -eq $expected[0])[0].Count
        if ($actual -ne $expected[1]) { throw "$($expected[0]) 题量异常：$actual" }
    }
    if ($questions[88]['image'] -ne $null -or
            $questions[88]['explanationImages'][0] -ne 'image/q89_answer.png') {
        throw '第 89 题答案图存在提前显示风险'
    }

    $bank = [ordered]@{ formatVersion = 2; name = $bankName; questions = $questions.ToArray() }
    $json = ConvertTo-Json -InputObject $bank -Depth 14
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
            $jsonEntry = $outputArchive.CreateEntry($jsonName,
                    [System.IO.Compression.CompressionLevel]::Optimal)
            $writer = [System.IO.StreamWriter]::new($jsonEntry.Open(),
                    [System.Text.UTF8Encoding]::new($false))
            try { $writer.Write($json) } finally { $writer.Dispose() }
            $null = $outputArchive.CreateEntry('image/')
            foreach ($imagePath in $packagedImages.Keys) {
                $sourceImage = $sourceArchive.GetEntry($packagedImages[$imagePath])
                if ($null -eq $sourceImage) { throw "源图片不存在：$($packagedImages[$imagePath])" }
                $outputImage = $outputArchive.CreateEntry($imagePath,
                        [System.IO.Compression.CompressionLevel]::Optimal)
                $inputStream = $sourceImage.Open()
                $outputStream = $outputImage.Open()
                try { $inputStream.CopyTo($outputStream) }
                finally { $inputStream.Dispose(); $outputStream.Dispose() }
            }
        } finally { $outputArchive.Dispose() }
        [System.IO.File]::WriteAllBytes($outputPath, $buffer.ToArray())
    } finally { $buffer.Dispose() }
    "Created $outputPath"
    "Questions: $($questions.Count); matching: $(@($questions | Where-Object type -eq 'matching').Count); images: $($packagedImages.Count); bytes: $((Get-Item -LiteralPath $outputPath).Length)"
} finally { $sourceArchive.Dispose() }
