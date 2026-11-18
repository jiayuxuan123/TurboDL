param(
    [Parameter(Mandatory=$true)][string]$Zig,
    [Parameter(Mandatory=$true)][string]$SourceDirectory,
    [Parameter(Mandatory=$true)][string]$BuildDirectory,
    [Parameter(Mandatory=$true)][string]$Prefix,
    [Parameter(Mandatory=$true)][string]$NdkDirectory,
    [string]$GitDirectory='C:\Program Files\Git',
    [switch]$SkipConfigure
)
$ErrorActionPreference='Stop'
$build=[IO.Path]::GetFullPath($BuildDirectory)
New-Item -ItemType Directory -Force -Path $build | Out-Null
$tools=Join-Path $build 'compiler-tools'
New-Item -ItemType Directory -Force -Path $tools | Out-Null
$cc=Join-Path $tools 'zig-cc.exe'
& $Zig c++ -std=c++20 -O2 (Join-Path $PSScriptRoot 'tools\zig_tool.cpp') -o $cc
if($LASTEXITCODE -ne 0){throw 'Compiler adapter build failed'}
Copy-Item -LiteralPath $cc -Destination (Join-Path $tools 'zig-ar.exe') -Force
Copy-Item -LiteralPath $cc -Destination (Join-Path $tools 'zig-ranlib.exe') -Force
$env:TD_ZIG_EXE=$Zig
$gitBin=Join-Path $GitDirectory 'usr\bin'
$env:PATH=$gitBin+';'+$env:PATH
$cygpath=Join-Path $gitBin 'cygpath.exe'
$sourcePosix=(& $cygpath -u ([IO.Path]::GetFullPath($SourceDirectory))).Trim()
$prefixPosix=(& $cygpath -u ([IO.Path]::GetFullPath($Prefix))).Trim()
$toolsPosix=(& $cygpath -u $tools).Trim()
$llvm=Join-Path $NdkDirectory 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$nmPosix=(& $cygpath -u (Join-Path $llvm 'llvm-nm.exe')).Trim()
$stripPosix=(& $cygpath -u (Join-Path $llvm 'llvm-strip.exe')).Trim()
$dlltool=Join-Path $llvm 'llvm-dlltool.exe'
Push-Location $build
try {
    if(-not $SkipConfigure){
    & (Join-Path $gitBin 'bash.exe') --noprofile --norc "$sourcePosix/configure" `
        "--prefix=$prefixPosix" "--cc=$toolsPosix/zig-cc.exe" "--host-cc=$toolsPosix/zig-cc.exe" "--ar=$toolsPosix/zig-ar.exe" `
        "--ranlib=$toolsPosix/zig-ranlib.exe" "--nm=$nmPosix" "--strip=$stripPosix" `
        --target-os=mingw32 --arch=x86_64 --enable-cross-compile --enable-shared --disable-static `
        --disable-everything --disable-autodetect --disable-network --disable-programs --disable-doc `
        --disable-debug --disable-asm --disable-x86asm --disable-gpl --disable-nonfree --disable-avdevice `
        --disable-avfilter --disable-swresample --disable-swscale --enable-small `
        --enable-demuxer=mov,mpegts,aac,h264,hevc --enable-muxer=mp4,mov,mpegts,adts `
        --enable-protocol=file --enable-parser=aac,h264,hevc `
        --enable-encoder=aac,mpeg4 --enable-decoder=aac,mpeg4 `
        --enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb
    if($LASTEXITCODE -ne 0){throw 'FFmpeg configuration failed'}
    }
    # Android NDK make is a native Windows binary, not MSYS make. Convert only
    # generated makefile paths; never modify the pinned upstream source tree.
    foreach($generated in @('Makefile','ffbuild/config.mak')){
        $path=Join-Path $build $generated
        $text=[IO.File]::ReadAllText($path)
        $text=[regex]::Replace($text,'/([a-zA-Z])/',{param($match) $match.Groups[1].Value.ToUpper()+':/'})
        [IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false))
    }
    $make=Join-Path $NdkDirectory 'prebuilt\windows-x86_64\bin\make.exe'
    & $make -j2 "DLLTOOL=$($dlltool.Replace('\','/'))"
    if($LASTEXITCODE -ne 0){throw 'FFmpeg library build failed'}
    # Recover an interrupted earlier install whose optional GNU dlltool command
    # was absent. Generate real import libraries with the verified NDK tool.
    foreach($component in @('avformat','avcodec','avutil')){
        $componentDir=Join-Path $build ('lib'+$component)
        $import=Join-Path $componentDir ($component+'.lib')
        if(-not (Test-Path -LiteralPath $import)){
            $definition=Get-ChildItem -LiteralPath $componentDir -Filter ($component+'-*.def') | Select-Object -First 1
            if(-not $definition){throw "Missing FFmpeg export definition: $component"}
            $dllName=[IO.Path]::GetFileNameWithoutExtension($definition.Name)+'.dll'
            & $dlltool -m i386:x86-64 -d $definition.FullName -l $import -D $dllName
            if($LASTEXITCODE -ne 0){throw "FFmpeg import library generation failed: $component"}
        }
    }
    & $make install
    if($LASTEXITCODE -ne 0){throw 'FFmpeg library install failed'}
    # Native Windows make routes this 17 KiB header-install command through
    # cmd.exe's 8 KiB limit. Install the exact planned public headers one at a
    # time so a successful make cannot silently leave libavutil empty.
    $installPlan=& $make -n install-headers
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect FFmpeg public headers'}
    $prefixFull=[IO.Path]::GetFullPath($Prefix)
    foreach($line in $installPlan){
        if($line -notmatch 'install -m 644 (?<sources>.+) "(?<destination>[^"]+/include/libav(?:format|codec|util))"'){continue}
        $destination=[IO.Path]::GetFullPath($Matches.destination)
        if(-not $destination.StartsWith($prefixFull+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Header destination escaped prefix'}
        $sources=$Matches.sources
        foreach($headerMatch in [regex]::Matches($sources,'(?:"(?<path>[^"]+\.h)"|(?<path>[^\s]+\.h))')){
            $headerPath=$headerMatch.Groups['path'].Value
            if(-not [IO.Path]::IsPathRooted($headerPath)){$headerPath=Join-Path $build $headerPath}
            $header=[IO.Path]::GetFullPath($headerPath)
            Copy-Item -LiteralPath $header -Destination $destination -Force
        }
    }
    if(-not (Test-Path -LiteralPath (Join-Path $prefixFull 'include\libavutil\avutil.h'))){throw 'FFmpeg public header installation incomplete'}
} finally {Pop-Location}
