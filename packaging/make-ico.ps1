param(
    [string]$SourcePng = "$PSScriptRoot\app-icon.png",
    [string]$OutputIco = "$PSScriptRoot\app-icon.ico"
)

Add-Type -AssemblyName System.Drawing

$sizes = @(16, 32, 48, 64, 128, 256)
$srcImg = [System.Drawing.Image]::FromFile((Resolve-Path $SourcePng))
$pngBlobs = New-Object System.Collections.Generic.List[byte[]]
$widthList = New-Object System.Collections.Generic.List[int]

foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($srcImg, 0, 0, $s, $s)
    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngBlobs.Add($ms.ToArray())
    $widthList.Add($s)
    $ms.Dispose()
    $bmp.Dispose()
}
$srcImg.Dispose()

$fs = [System.IO.File]::Create($OutputIco)
$bw = New-Object System.IO.BinaryWriter $fs
$count = $pngBlobs.Count
$bw.Write([UInt16]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]$count)

$headerSize = 6 + (16 * $count)
$offset = $headerSize
for ($i = 0; $i -lt $count; $i++) {
    $s = $widthList[$i]
    $data = $pngBlobs[$i]
    $dim = 0
    if ($s -lt 256) { $dim = $s }
    $bw.Write([byte]$dim)
    $bw.Write([byte]$dim)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]$data.Length)
    $bw.Write([UInt32]$offset)
    $offset += $data.Length
}
for ($i = 0; $i -lt $count; $i++) {
    $bw.Write($pngBlobs[$i])
}
$bw.Flush()
$bw.Close()
$fs.Close()

Write-Host "Wrote $OutputIco ($((Get-Item $OutputIco).Length) bytes)"
