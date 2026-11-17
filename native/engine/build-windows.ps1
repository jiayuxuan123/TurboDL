param(
    [Parameter(Mandatory=$true)][string]$Zig,
    [Parameter(Mandatory=$true)][string]$CMake,
    [Parameter(Mandatory=$true)][string]$CurlRoot,
    [Parameter(Mandatory=$true)][string]$SqliteSource,
    [Parameter(Mandatory=$true)][string]$BuildDirectory
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = [IO.Path]::GetFullPath($BuildDirectory)
New-Item -ItemType Directory -Force -Path $build | Out-Null
$cc = Join-Path $build 'zig-cc.cmd'
$cxx = Join-Path $build 'zig-cxx.cmd'
$ar = Join-Path $build 'zig-ar.cmd'
$ranlib = Join-Path $build 'zig-ranlib.cmd'
[IO.File]::WriteAllText($cc, "@echo off`r`n`"$Zig`" cc -target x86_64-windows-gnu %*`r`n", [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($cxx, "@echo off`r`n`"$Zig`" c++ -target x86_64-windows-gnu %*`r`n", [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($ar, "@echo off`r`n`"$Zig`" ar %*`r`n", [Text.Encoding]::ASCII)
[IO.File]::WriteAllText($ranlib, "@echo off`r`n`"$Zig`" ranlib %*`r`n", [Text.Encoding]::ASCII)
$ninja = Join-Path (Split-Path $CMake) 'ninja.exe'
& $CMake -S $root -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" "-DCMAKE_AR=$ar" "-DCMAKE_RANLIB=$ranlib" "-DCMAKE_BUILD_TYPE=Debug" "-DTD_CURL_ROOT=$CurlRoot" "-DTD_SQLITE_SOURCE=$SqliteSource"
if ($LASTEXITCODE -ne 0) { throw 'Native configuration failed' }
& $CMake --build $build --parallel 2
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
