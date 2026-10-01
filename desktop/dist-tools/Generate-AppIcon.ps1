# Draws the Windows application icon from the same coordinates as the Android one.
#
# The Android launcher icon is app/src/main/res/drawable/ic_launcher_foreground.xml,
# and ic_launcher_background.xml beside it. This rasterises those same polygons into
# a multi-size .ico so the two platforms are the same drawing rather than two
# drawings that look alike. Change one, re-run this.
#
#   powershell -ExecutionPolicy Bypass -File desktop\dist-tools\Generate-AppIcon.ps1
#
# The result is committed, so a normal build never runs this.
#
# The one deliberate difference: Windows has no launcher mask, so the rounded black
# square is drawn here rather than left to the platform. Android gets it from the
# launcher's own mask instead, which is why its background vector is a plain full
# bleed with no corners.

param(
    [string]$OutputPath = (Join-Path $PSScriptRoot 'app-icon.ico'),
    # Where to also write the PNG sizes the browser extension needs. The extension
    # is a third surface showing the same mark, so it is drawn from these
    # coordinates rather than from a copy of them: a second copy of this drawing is
    # how the tray, the .ico and the launcher quietly stop matching.
    [string]$PngOutDir = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# The Android vectors use a 108-unit viewport and the shapes below are copied from
# them coordinate for coordinate.
$Design = 108.0
$Inset = 3.0   # keeps a sliver of transparency outside the rounded square
$CornerRadius = 22.0

# --- the artwork, in the 108-unit viewport ---------------------------------

# Folder body with a tab on the left.
$Folder = @(
    @(34,38), @(36,36), @(43,36), @(43,31), @(45,29), @(57,29), @(59,31),
    @(59,36), @(72,36), @(74,38), @(74,58), @(72,60), @(36,60), @(34,58)
)

# The download arrow, punched out of the folder.
$Arrow = @(
    @(49,39), @(59,39), @(59,48), @(66,48), @(54,58), @(42,48), @(49,48)
)

# "1DM" - a vector cannot contain text, so these are paths on the Android side too.
$Letter1 = @(
    @(42,66), @(46,66), @(46,78), @(42,78), @(42,71), @(38,75), @(36,71)
)
$LetterDOuter = @(
    @(50,66), @(56,66), @(59,68), @(60,72), @(59,76), @(56,78), @(50,78)
)
$LetterDCounter = @(
    @(54,69), @(54,75), @(56,75), @(56,69)
)
$LetterM = @(
    @(63,78), @(63,66), @(66,66), @(68,72), @(70,66), @(73,66), @(73,78),
    @(70,78), @(70,70), @(69,75), @(67,75), @(66,70), @(66,78)
)

function To-Points([double]$scale, [object[]]$Shape) {
    $points = New-Object System.Collections.Generic.List[System.Drawing.PointF]
    foreach ($pair in $Shape) {
        $points.Add((New-Object System.Drawing.PointF ([float]($pair[0] * $scale)), ([float]($pair[1] * $scale))))
    }
    return [System.Drawing.PointF[]]$points.ToArray()
}

function New-IconBitmap([int]$side) {
    # Supersampled, because a diagonal drawn straight onto a 16 px icon is a
    # staircase and the taskbar is where most people see it.
    $scale = 4
    $canvas = New-Object System.Drawing.Bitmap ($side * $scale), ($side * $scale)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $k = ($side * $scale) / $Design

    # The rounded black square. Drawn as a path with quadratic corners rather than
    # GraphicsPath arcs, so it matches the polygon treatment used everywhere else.
    $left = [float]($Inset * $k)
    $right = [float](($Design - $Inset) * $k)
    $top = [float]($Inset * $k)
    $bottom = [float](($Design - $Inset) * $k)
    $r = [float]($CornerRadius * $k)
    $square = New-Object System.Drawing.Drawing2D.GraphicsPath
    $square.AddArc($left, $top, $r, $r, 180, 90)
    $square.AddArc($right - $r, $top, $r, $r, 270, 90)
    $square.AddArc($right - $r, $bottom - $r, $r, $r, 0, 90)
    $square.AddArc($left, $bottom - $r, $r, $r, 90, 90)
    $square.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)), $square)

    $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $black = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)

    $g.FillPolygon($white, (To-Points $k $Folder))

    # The D is two rings, so it is filled with the even-odd rule: the counter is a
    # hole rather than a second colour, which is what keeps it correct if the folder
    # colour ever changes.
    $d = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d.FillMode = [System.Drawing.Drawing2D.FillMode]::Alternate
    $d.AddPolygon((To-Points $k $LetterDOuter))
    $d.AddPolygon((To-Points $k $LetterDCounter))
    $g.FillPath($white, $d)

    $g.FillPolygon($black, (To-Points $k $Arrow))
    $g.FillPolygon($white, (To-Points $k $Letter1))
    $g.FillPolygon($white, (To-Points $k $LetterM))

    $g.Dispose()
    $white.Dispose(); $black.Dispose(); $square.Dispose(); $d.Dispose()

    $final = New-Object System.Drawing.Bitmap $side, $side
    $g2 = [System.Drawing.Graphics]::FromImage($final)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g2.DrawImage($canvas, 0, 0, $side, $side)
    $g2.Dispose()
    $canvas.Dispose()
    return $final
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
