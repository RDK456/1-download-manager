# Draws the Windows application icon from the same glyph as the Android one.
#
# The Android launcher icon is app/src/main/res/drawable/ic_launcher_foreground.xml
# (Material Symbols Rounded "download", filled, weight 700, in white) over
# ic_launcher_background.xml (the app's indigo). This renders that same path into a
# multi-size .ico, so the two platforms are the same drawing. Change one, re-run this.
#
#   powershell -ExecutionPolicy Bypass -File desktop\dist-tools\Generate-AppIcon.ps1 -PngOutDir "desktop\browser-extension\chromium;desktop\browser-extension\firefox"
#
# The result is committed, so a normal build never runs this.
#
# The glyph is SVG path data, which WPF's geometry parser reads as is. Windows has no
# launcher mask, so the rounded square is drawn here, and only the central 64 units of
# the launcher's 108-unit viewport (22 to 86) are used so the glyph fills the icon.

param(
    [string]$OutputPath = (Join-Path $PSScriptRoot 'app-icon.ico'),
    # Where to also write the PNG sizes the browser extension needs, ';'-separated.
    [string]$PngOutDir = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName PresentationCore, WindowsBase

# Google's glyph exactly as published, in its 960-unit box (y from -960 to 0).
$Glyph = 'M462.05-345.05Q453.1-349.09 446-356L284-518q-14-14.27-13.5-33.64Q271-571 284.61-585q14.79-14.15 34.09-13.58Q338-598 352-584l81 81v-275q0-19.63 13.68-33.81Q460.35-826 480.18-826q19.82 0 33.32 14.19Q527-797.63 527-778v275l82-81q13.8-14 32.25-14.58 18.45-.58 32.91 13.5Q689-571 688.5-551.18T674-517L514-356q-7.17 6.91-16.33 10.95-9.16 4.05-17.91 4.05-8.76 0-17.71-4.05ZM229-135q-39.05 0-66.52-27.48Q135-189.95 135-229v-96q0-19.75 13.68-33.38Q162.35-372 182.18-372q19.82 0 33.32 13.62Q229-344.75 229-325v96h502v-96q0-19.75 13.68-33.38Q758.35-372 778.09-372q19.73 0 33.82 13.62Q826-344.75 826-325v96q0 39.05-27.77 66.52Q770.46-135 731-135H229Z'

$Top = [System.Windows.Media.Color]::FromRgb(0x74, 0x80, 0xF0)
$Bottom = [System.Windows.Media.Color]::FromRgb(0x3F, 0x4A, 0xB8)

function New-IconBitmap([int]$side) {
    $u = $side / 64.0
    $visual = New-Object System.Windows.Media.DrawingVisual
    $dc = $visual.RenderOpen()
    $brush = New-Object System.Windows.Media.LinearGradientBrush $Top, $Bottom, (New-Object System.Windows.Point 0, 0), (New-Object System.Windows.Point 1, 1)
    $dc.DrawRoundedRectangle($brush, $null, (New-Object System.Windows.Rect 0, 0, $side, $side), (14 * $u), (14 * $u))
    # Glyph box -> launcher viewport (centred on 54,54 at 0.066) -> the 22..86 crop.
    $k = 0.066 * $u
    $dc.PushTransform((New-Object System.Windows.Media.MatrixTransform $k, 0, 0, $k, ((54 - 22 - 480 * 0.066) * $u), ((54 - 22 + 480 * 0.066) * $u)))
    $dc.DrawGeometry([System.Windows.Media.Brushes]::White, $null, [System.Windows.Media.Geometry]::Parse($Glyph))
    $dc.Pop()
    $dc.Close()

    $target = New-Object System.Windows.Media.Imaging.RenderTargetBitmap $side, $side, 96, 96, ([System.Windows.Media.PixelFormats]::Pbgra32)
    $target.Render($visual)
    $encoder = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($target))
    $stream = New-Object System.IO.MemoryStream
    $encoder.Save($stream)
    $stream.Position = 0
    return New-Object System.Drawing.Bitmap $stream
}

function ConvertTo-PngBytes([System.Drawing.Bitmap]$bitmap) {
    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $stream.ToArray()
    $stream.Dispose()
    $bitmap.Dispose()
    return $bytes
}

# Windows picks from these by size: 16 for the taskbar and Alt-Tab, 32 for a
# normal desktop shortcut, 256 for Explorer's large-icons view.
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = @()
foreach ($size in $sizes) {
    $images += , [PSCustomObject]@{ Size = $size; Bytes = ConvertTo-PngBytes (New-IconBitmap $size) }
}

# ICONDIR + one ICONDIRENTRY per image + the image data, assembled byte by byte.
#
# Written by hand rather than with BinaryWriter: passing a byte[] to BinaryWriter
# from PowerShell resolves to an overload that silently writes nothing, which
# produced a 125-byte file full of correct-looking offsets and no pictures.
$out = New-Object System.Collections.Generic.List[byte]

function Add-U16([int]$value) {
    $out.Add([byte]($value -band 0xFF))
    $out.Add([byte](($value -shr 8) -band 0xFF))
}

function Add-U32([long]$value) {
    $out.Add([byte]($value -band 0xFF))
    $out.Add([byte](($value -shr 8) -band 0xFF))
    $out.Add([byte](($value -shr 16) -band 0xFF))
    $out.Add([byte](($value -shr 24) -band 0xFF))
}

Add-U16 0                  # reserved
Add-U16 1                  # 1 = icon
Add-U16 $images.Count

$offset = 6 + (16 * $images.Count)
foreach ($image in $images) {
    # A dimension of 0 means 256, which is what the format uses.
    $dimension = if ($image.Size -ge 256) { 0 } else { $image.Size }
    $out.Add([byte]$dimension)
    $out.Add([byte]$dimension)
    $out.Add([byte]0)     # palette size
    $out.Add([byte]0)     # reserved
    Add-U16 1             # colour planes
    Add-U16 32            # bits per pixel
    Add-U32 $image.Bytes.Length
    Add-U32 $offset
    $offset += $image.Bytes.Length
}
foreach ($image in $images) {
    foreach ($byte in $image.Bytes) { $out.Add($byte) }
}

[System.IO.File]::WriteAllBytes($OutputPath, $out.ToArray())
Write-Host "Wrote $OutputPath ($([math]::Round((Get-Item $OutputPath).Length / 1KB, 1)) KB, $($sizes -join ', ') px)"

# The extension's icons, from the same drawing.
#
# Both stores require them - Chrome Web Store rejects an upload with no icons
# array at all - and the extension had none, which is part of why it could only
# ever be loaded unpacked from inside the app. Only 16/32/48/128 are written:
# those are the four sizes both stores ask for, and they are also what a toolbar
# and a store listing render at, so nothing is generated that nothing shows.
if ($PngOutDir) {
    foreach ($dir in $PngOutDir.Split(';')) {
        $target = $dir.Trim()
        if (-not $target) { continue }
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        foreach ($size in @(16, 32, 48, 128)) {
            $png = Join-Path $target "icon$size.png"
            [System.IO.File]::WriteAllBytes($png, (ConvertTo-PngBytes (New-IconBitmap $size)))
        }
        Write-Host "Wrote extension icons to $target (16, 32, 48, 128 px)"
    }
}
