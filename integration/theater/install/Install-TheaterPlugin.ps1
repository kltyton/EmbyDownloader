param(
    [Parameter(Mandatory=$true)][string]$ClientDirectory,
    [string]$PackageDirectory = (Split-Path -Parent $PSScriptRoot),
    [string]$FFmpegPath
)
$ErrorActionPreference = 'Stop'
$client = (Resolve-Path -LiteralPath $ClientDirectory).Path
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$electronApp = Join-Path $client 'electronapp'
$preload = Join-Path $electronApp 'preload.js'
$manifest = Join-Path $electronApp 'package.json'
if (-not (Test-Path -LiteralPath $preload) -or -not (Test-Path -LiteralPath $manifest)) { throw 'The target is not an Electron-based Emby Theater installation.' }
if ((Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json).name -ne 'emby-theater') { throw 'The target application is not Emby Theater.' }
$running = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($client + '\', [StringComparison]::OrdinalIgnoreCase) }
if ($running) { throw 'Close Emby Theater before installing the plugin.' }
$backend = Join-Path $package 'backend'
if (-not (Test-Path -LiteralPath (Join-Path $backend 'runtime\bin\java.exe'))) { throw 'The plugin package is missing its backend runtime.' }
$plugins = Join-Path $electronApp 'plugins'
$destination = Join-Path $plugins 'embydownload'
New-Item -ItemType Directory -Path $destination -ErrorAction SilentlyContinue | Out-Null
Copy-Item -LiteralPath (Join-Path $package 'plugin\embydownload.js') -Destination (Join-Path $plugins 'embydownload.js')
Get-ChildItem -LiteralPath (Join-Path $package 'plugin\embydownload') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $destination $_.Name) }
$backendDestination = Join-Path $destination 'backend'
New-Item -ItemType Directory -Path $backendDestination -ErrorAction SilentlyContinue | Out-Null
[IO.Directory]::EnumerateFiles($backend, '*', [IO.SearchOption]::AllDirectories) | ForEach-Object {
    $sourceFile = $_
    $relative = $sourceFile.Substring($backend.Length).TrimStart('\')
    $targetFile = Join-Path $backendDestination $relative
    $parentDirectory = Split-Path -Parent $targetFile
    if (-not (Test-Path -LiteralPath $parentDirectory)) { New-Item -ItemType Directory -Path $parentDirectory | Out-Null }
    [IO.File]::Copy($sourceFile, $targetFile, $true)
}
if (-not $FFmpegPath) { $command = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue; if ($command) { $FFmpegPath = $command.Source } }
if ($FFmpegPath) { Copy-Item -LiteralPath (Resolve-Path -LiteralPath $FFmpegPath).Path -Destination (Join-Path $backendDestination 'native\windows\ffmpeg.exe') }
$hook = "require('./plugins/embydownload/bridge.cjs').install();"
$content = [IO.File]::ReadAllText($preload)
if (-not $content.Contains($hook)) { [IO.File]::WriteAllText($preload, $content.TrimEnd() + [Environment]::NewLine + $hook + [Environment]::NewLine, (New-Object Text.UTF8Encoding $false)) }
Write-Output 'EmbyDownload installed. Start Emby Theater and open Download from its header or media detail page.'
