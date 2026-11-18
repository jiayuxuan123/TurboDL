param(
    [Parameter(Mandatory = $true)][string]$Ndk,
    [Parameter(Mandatory = $true)][string]$Zig,
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$GitDirectory = 'C:\Program Files\Git',
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')]
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'),
    [ValidateRange(1, 32)][int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$llvm = (Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin').Replace('\', '/')
$make = Join-Path $Ndk 'prebuilt/windows-x86_64/bin/make.exe'
$gitBin = Join-Path $GitDirectory 'usr/bin'
$source = [IO.Path]::GetFullPath($SourceDirectory).Replace('\', '/')
$hostCompiler = (Join-Path $OutputRoot 'zig-host-cc.exe').Replace('\', '/')
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
& $Zig c++ -std=c++20 -O2 (Join-Path $PSScriptRoot 'tools/zig_tool.cpp') -o $hostCompiler
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg host compiler adapter failed' }
$env:TD_ZIG_EXE = $Zig
$env:PATH = $gitBin + ';' + $env:PATH

$targets = @{
    'arm64-v8a' = @('aarch64-linux-android24', 'aarch64', 'armv8-a')
    'armeabi-v7a' = @('armv7a-linux-androideabi24', 'arm', 'armv7-a')
    'x86_64' = @('x86_64-linux-android24', 'x86_64', 'x86-64')
    'x86' = @('i686-linux-android24', 'x86', 'i686')
}
foreach ($abi in $Abis) {
    $triple, $arch, $cpu = $targets[$abi]
    $build = Join-Path $OutputRoot "build/$abi"
    $prefix = (Join-Path $OutputRoot "prefix/$abi").Replace('\', '/')
    New-Item -ItemType Directory -Force -Path $build | Out-Null
    Push-Location $build
    try {
        & (Join-Path $gitBin 'bash.exe') --noprofile --norc "$source/configure" `
            "--prefix=$prefix" "--cc=$llvm/$triple-clang.cmd" "--cxx=$llvm/$triple-clang++.cmd" `
            "--ar=$llvm/llvm-ar.exe" "--ranlib=$llvm/llvm-ranlib.exe" "--nm=$llvm/llvm-nm.exe" `
            "--strip=$llvm/llvm-strip.exe" "--host-cc=$hostCompiler" --host-cflags=-D_ISOC11_SOURCE `
            --target-os=android "--arch=$arch" "--cpu=$cpu" --enable-cross-compile `
            --disable-shared --enable-static --disable-everything --disable-autodetect --disable-network `
            --disable-programs --disable-doc --disable-debug --disable-asm --disable-x86asm `
            --disable-gpl --disable-nonfree --disable-avdevice --disable-avfilter --disable-swresample `
            --disable-swscale --enable-small '--enable-demuxer=mov,mpegts,aac,h264,hevc' `
            '--enable-muxer=mp4,mov,mpegts,adts' --enable-protocol=file '--enable-parser=aac,h264,hevc' `
            '--enable-encoder=aac,mpeg4' '--enable-decoder=aac,mpeg4' `
            '--enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb'
        if ($LASTEXITCODE -ne 0) { throw "FFmpeg configure failed for $abi" }

        foreach ($generated in @('Makefile', 'ffbuild/config.mak')) {
            $path = Join-Path $build $generated
            $contents = [IO.File]::ReadAllText($path)
            $contents = [regex]::Replace($contents, '(^|[\s="])/(\w)/', {
                param($match)
                $match.Groups[1].Value + $match.Groups[2].Value.ToUpperInvariant() + ':/'
            })
            [IO.File]::WriteAllText($path, $contents, [Text.UTF8Encoding]::new($false))
        }
        & $make "-j$Jobs"
        if ($LASTEXITCODE -ne 0) { throw "FFmpeg build failed for $abi" }
        & $make install-libs
        if ($LASTEXITCODE -ne 0) { throw "FFmpeg archive install failed for $abi" }

        # The native Windows make cannot pass the longest header install line
        # through cmd.exe. Copy the exact public header list from its dry run.
        $installPlan = & $make -n install-headers
        if ($LASTEXITCODE -ne 0) { throw "Cannot read FFmpeg header install plan for $abi" }
        foreach ($line in $installPlan) {
            if ($line -notmatch 'install -m 644 (?<sources>.+) "(?<destination>[^"]+/include/libav(?:format|codec|util))"') { continue }
            $destination = $Matches.destination
            if (!$destination.StartsWith($prefix + '/', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'FFmpeg header destination escaped the build prefix'
            }
            New-Item -ItemType Directory -Force -Path $destination | Out-Null
            foreach ($header in [regex]::Matches($Matches.sources, '(?:"(?<path>[^"]+\.h)"|(?<path>[^\s]+\.h))')) {
                Copy-Item -LiteralPath $header.Groups['path'].Value -Destination $destination
            }
        }
        foreach ($component in @('avformat', 'avcodec', 'avutil')) {
            if (!(Test-Path -LiteralPath "$prefix/include/lib$component/$component.h")) {
                throw "FFmpeg public headers are missing for $abi/$component"
            }
        }
    } finally {
        Pop-Location
    }
}
