# ProjectMemo memo-server 构建脚本（Windows / PowerShell）
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Lib = Resolve-Path "..\lib"
$JdkBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin" }
$Cls = Join-Path $PSScriptRoot "build\classes"

if (Test-Path $Cls) { Remove-Item -Recurse -Force $Cls }
New-Item -ItemType Directory -Force -Path $Cls | Out-Null

$jars = Get-ChildItem $Lib -Filter *.jar
$cp = ($jars | ForEach-Object { $_.FullName }) -join ";"
$sources = (Get-ChildItem "src\main\java\com\sthstrange\projectmemo" -Filter *.java).FullName

Write-Host "[build] compiling (release 21)..."
& "$JdkBin\javac.exe" -encoding UTF-8 -proc:none --release 21 -cp $cp -d $Cls $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[build] copying resources..."
Copy-Item "src\main\resources\plugin.yml" $Cls
Copy-Item "src\main\resources\config.yml" $Cls

Write-Host "[build] shading json + adventure-plain..."
Push-Location $Cls
foreach ($dep in ($jars | Where-Object { $_.Name -match '^(json-|adventure-text-serializer-plain)' })) {
    & "$JdkBin\jar.exe" xf $dep.FullName
}
Pop-Location
if (Test-Path "$Cls\META-INF") { Remove-Item -Recurse -Force "$Cls\META-INF" }

Write-Host "[build] packaging..."
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot "build") | Out-Null
Push-Location $Cls
& "$JdkBin\jar.exe" cf ..\ProjectMemo-1.2.0.jar .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Pop-Location

Write-Host "[build] done:"
Get-Item (Join-Path $PSScriptRoot "build\ProjectMemo-1.2.0.jar") | Select-Object FullName,Length
