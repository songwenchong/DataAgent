param(
    [Parameter(Mandatory = $true)]
    [string]$Title,

    [Parameter(Mandatory = $true)]
    [string]$RequestFile,

    [Parameter(Mandatory = $true)]
    [string]$AnalysisFile,

    [Parameter(Mandatory = $true)]
    [string]$ConclusionFile,

    [Parameter(Mandatory = $true)]
    [string]$PlanFile,

    [string]$OutputDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $defaultFolderName = [string]::Concat(([char[]](26032,24314,25991,20214,22841)))
    $OutputDir = "C:\Users\zzht_s\Desktop\$defaultFolderName"
}

function Read-Utf8File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "File not found: $Path"
    }

    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8).Trim()
}

function Get-SafeFileName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $invalidChars = [System.IO.Path]::GetInvalidFileNameChars()
    $safe = $Name
    foreach ($char in $invalidChars) {
        $safe = $safe.Replace($char, "-")
    }

    $safe = ($safe -replace "\s+", "-").Trim("-")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return "analysis-plan"
    }

    return $safe
}

$request = Read-Utf8File -Path $RequestFile
$analysis = Read-Utf8File -Path $AnalysisFile
$conclusion = Read-Utf8File -Path $ConclusionFile
$plan = Read-Utf8File -Path $PlanFile

if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$filenameTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$safeTitle = Get-SafeFileName -Name $Title
$outputPath = Join-Path $OutputDir "$filenameTimestamp-$safeTitle.md"

$content = @"
# $Title

- Time: $timestamp
- Request Summary: $request

## Analysis

$analysis

## Conclusion

$conclusion

## Phased Execution Plan

$plan
"@

[System.IO.File]::WriteAllText($outputPath, $content.Trim() + [Environment]::NewLine, [System.Text.Encoding]::UTF8)
Write-Output $outputPath
