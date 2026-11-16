#requires -Version 5.1
<#
  Renames the product brand across the whole repository.

  Usage:
    .\scripts\rename-brand.ps1 -Old <old> -New <new>

  What it does:
    1. content rewrite in every text file: <old> -> <new>, <Old> -> <New>, <OLD> -> <NEW>
    2. renames files whose name carries the brand (e.g. <Old>Application.java)
    3. renames directories whose name carries the brand (e.g. com/<old> -> com/<new>)

  Notes:
    - .git / node_modules / target / dist are skipped
    - encoding is preserved (UTF-8 with or without BOM, or GBK fallback)
    - binary files are skipped by extension
    - the rewrite is symmetric: running it again with -Old <new> -New <old> reverts it
#>
param(
    [Parameter(Mandatory = $true)][string]$Old,
    [Parameter(Mandatory = $true)][string]$New
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$SkipDirs = @('.git', 'node_modules', 'target', 'dist', '.tmp-avatar')

$BinaryExt = @(
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.bmp',
    '.woff', '.woff2', '.ttf', '.otf', '.eot',
    '.pdf', '.wav', '.mp3', '.mp4', '.mov',
    '.zip', '.gz', '.7z', '.rar', '.jar', '.class', '.war',
    '.xlsx', '.docx', '.pptx', '.db', '.bin', '.pdb', '.exe', '.dll', '.so'
)

$oLow = $Old; $nLow = $New
$oCap = $Old.Substring(0, 1).ToUpper() + $Old.Substring(1); $nCap = $New.Substring(0, 1).ToUpper() + $New.Substring(1)
$oUp = $Old.ToUpper(); $nUp = $New.ToUpper()
$oCapLow = $oCap.ToLower(); $nCapLow = $nCap.ToLower()

function Test-SkipDir([string]$path) {
    foreach ($d in $SkipDirs) {
        if ($path -match ('\\' + [regex]::Escape($d) + '\\')) { return $true }
    }
    return $false
}

function Get-TextFile([System.IO.FileInfo]$f) {
    if ($BinaryExt -contains $f.Extension.ToLower()) { return $null }
    try { $bytes = [System.IO.File]::ReadAllBytes($f.FullName) } catch { return $null }
    if ($bytes.Length -eq 0) { return $null }
    $sample = if ($bytes.Length -gt 8192) { $bytes[0..8191] } else { $bytes }
    if ($sample -contains 0) { return $null }          # NUL byte => binary
    return $bytes
}

function Read-WithEncoding([byte[]]$bytes) {
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        return @{ Encoding = New-Object System.Text.UTF8Encoding($true); Text = (New-Object System.Text.UTF8Encoding($false)).GetString($bytes, 3, $bytes.Length - 3) }
    }
    try {
        $strict = New-Object System.Text.UTF8Encoding($false, $true)
        $text = $strict.GetString($bytes)
        return @{ Encoding = New-Object System.Text.UTF8Encoding($false); Text = $text }
    } catch {
        $gbk = [System.Text.Encoding]::GetEncoding(936)
        return @{ Encoding = $gbk; Text = $gbk.GetString($bytes) }
    }
}

# ---------------------------------------------------------------- content pass
$changed = 0
$scanned = 0
$files = Get-ChildItem -LiteralPath $Root -Recurse -File -Force | Where-Object { -not (Test-SkipDir $_.FullName) }

foreach ($f in $files) {
    $bytes = Get-TextFile $f
    if ($null -eq $bytes) { continue }
    $scanned++
    $r = Read-WithEncoding $bytes
    $text = $r.Text
    if ($text.IndexOf($oLow, [System.StringComparison]::Ordinal) -lt 0 -and
        $text.IndexOf($oCap, [System.StringComparison]::Ordinal) -lt 0 -and
        $text.IndexOf($oUp, [System.StringComparison]::Ordinal) -lt 0) { continue }

    $new = $text.Replace($oUp, $nUp).Replace($oCapLow, $nCapLow).Replace($oCap, $nCap).Replace($oLow, $nLow)
    if ($new -ceq $text) { continue }
    [System.IO.File]::WriteAllText($f.FullName, $new, $r.Encoding)
    $changed++
}
Write-Host ("content: {0} file(s) rewritten, {1} text file(s) scanned" -f $changed, $scanned)

# ------------------------------------------------------------- file name pass
$fileRenamed = 0
$files = Get-ChildItem -LiteralPath $Root -Recurse -File -Force | Where-Object { -not (Test-SkipDir $_.FullName) }
foreach ($f in $files) {
    if ($f.Name -match [regex]::Escape($oLow) -or $f.Name -match [regex]::Escape($oCap)) {
        $newName = $f.Name.Replace($oUp, $nUp).Replace($oCap, $nCap).Replace($oLow, $nLow)
        if ($newName -ne $f.Name) {
            Rename-Item -LiteralPath $f.FullName -NewName $newName
            $fileRenamed++
        }
    }
}
Write-Host ("files: {0} renamed" -f $fileRenamed)

# ------------------------------------------------------------ directory pass
$dirRenamed = 0
$dirs = Get-ChildItem -LiteralPath $Root -Recurse -Directory -Force |
    Where-Object { -not (Test-SkipDir ($_.FullName + '\')) } |
    Sort-Object { $_.FullName.Length } -Descending

foreach ($d in $dirs) {
    if (-not $d.Exists) { continue }
    if ($d.FullName -eq $Root) { continue }   # never rename the repo root itself
    if ($d.Name -match [regex]::Escape($oLow) -or $d.Name -match [regex]::Escape($oCap)) {
        $newName = $d.Name.Replace($oUp, $nUp).Replace($oCap, $nCap).Replace($oLow, $nLow)
        if ($newName -ne $d.Name) {
            Rename-Item -LiteralPath $d.FullName -NewName $newName
            $dirRenamed++
        }
    }
}
Write-Host ("dirs: {0} renamed" -f $dirRenamed)
