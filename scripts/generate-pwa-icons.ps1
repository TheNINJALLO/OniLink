param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\OniLink\dashboard-ui\public\icons")
)

Add-Type -AssemblyName System.Drawing
[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

foreach ($size in 180, 192, 512) {
    $bitmap = [System.Drawing.Bitmap]::new($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml("#07111d"))

    $scale = $size / 512.0
    $cyan = [System.Drawing.Pen]::new(
        [System.Drawing.ColorTranslator]::FromHtml("#43c5e8"), 34 * $scale)
    $green = [System.Drawing.Pen]::new(
        [System.Drawing.ColorTranslator]::FromHtml("#52b7a8"), 38 * $scale)
    $green.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $green.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $green.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round

    $graphics.DrawEllipse($cyan, 102 * $scale, 102 * $scale, 308 * $scale, 308 * $scale)
    $points = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(178 * $scale, 319 * $scale),
        [System.Drawing.PointF]::new(178 * $scale, 193 * $scale),
        [System.Drawing.PointF]::new(334 * $scale, 319 * $scale),
        [System.Drawing.PointF]::new(334 * $scale, 193 * $scale)
    )
    $graphics.DrawLines($green, $points)

    $path = Join-Path $OutputDirectory "onilink-$size.png"
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $green.Dispose()
    $cyan.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
    Write-Output $path
}
