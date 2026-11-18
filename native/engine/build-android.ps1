param(
    [Parameter(Mandatory = $true)][string]$Ndk,
    [Parameter(Mandatory = $true)][string]$Cmake,
    [Parameter(Mandatory = $true)][string]$SourceCache,
    [Parameter(Mandatory = $true)][string]$DependencyPrefixes,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$MediaPrefixes = $DependencyPrefixes,
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')]
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'),
    [ValidateRange(1, 32)][int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'
$readelf = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
$strip = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
$ninja = Join-Path (Split-Path -Parent $Cmake) 'ninja.exe'
$requiredSources = @{
    TD_SQLITE_SOURCE = 'sqlite-amalgamation-3540100'
    TD_PUGIXML_SOURCE = 'media/pugixml-1.16'
    TD_LIBTORRENT_SOURCE = 'p2p/libtorrent-rasterbar-2.1.2'
    TD_BOOST_ROOT = 'boost-headers'
}
$sourceOptions = foreach($entry in $requiredSources.GetEnumerator()){
    "-D$($entry.Key)=$(Join-Path $SourceCache $entry.Value)"
}
$transportLibraries = @('libcurl.a', 'libssl.a', 'libcrypto.a', 'libnghttp2.a',
    'libnghttp3.a', 'libngtcp2.a', 'libngtcp2_crypto_libressl.a', 'libssh2.a')
$mediaLibraries = @('libavformat.a', 'libavcodec.a', 'libavutil.a')
$missing = @($Cmake, $ninja, $toolchain, $readelf, $strip) | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
foreach($abi in $Abis){
    $prefix = Join-Path $DependencyPrefixes $abi
    foreach($library in $transportLibraries){
        $artifact = Join-Path $prefix "lib/$library"
        if(-not (Test-Path -LiteralPath $artifact -PathType Leaf)){ $missing = @($missing) + $artifact }
    }
    foreach($library in $mediaLibraries){
        $artifact = Join-Path (Join-Path $MediaPrefixes $abi) "lib/$library"
        if(-not (Test-Path -LiteralPath $artifact -PathType Leaf)){ $missing = @($missing) + $artifact }
    }
}
if($missing){ throw "Missing Android toolchain or ABI-specific libraries:`n$($missing -join "`n")" }

foreach($abi in $Abis){
    $prefix = (Resolve-Path (Join-Path $DependencyPrefixes $abi)).Path
    $media = (Resolve-Path (Join-Path $MediaPrefixes $abi)).Path
    $build = Join-Path $OutputRoot "build/$abi"
    & $Cmake -S $PSScriptRoot -B $build -G Ninja @sourceOptions `
        "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DCMAKE_PREFIX_PATH=$prefix" "-DCMAKE_FIND_ROOT_PATH=$prefix" "-DTD_CURL_ROOT=$prefix" `
        "-DTD_CRYPTO_ROOT=$prefix" "-DOPENSSL_ROOT_DIR=$prefix" "-DTD_FFMPEG_ROOT=$media" `
        "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static `
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release `
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384' `
        -DCURL_USE_STATIC_LIBS=ON -DOPENSSL_USE_STATIC_LIBS=ON `
        -DTD_BUILD_JNI=ON -DTD_BUILD_CLI=OFF -DBUILD_TESTING=OFF `
        -DTD_ENABLE_MEDIA=ON -DTD_ENABLE_BITTORRENT=ON -DTD_REQUIRE_FULL_TRANSPORT=ON
    if($LASTEXITCODE -ne 0){ throw "Android configuration failed for $abi" }
    & $Cmake --build $build --target turbodl_engine --parallel $Jobs
    if($LASTEXITCODE -ne 0){ throw "Android compilation failed for $abi" }

    $destination = Join-Path $OutputRoot "jniLibs/$abi"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $binary = Join-Path $build 'libturbodl_engine.packaged.so'
    & $strip --strip-unneeded -o $binary (Join-Path $build 'libturbodl_engine.so')
    if($LASTEXITCODE -ne 0){ throw "Could not strip Android library for $abi" }
    $elf = & $readelf --wide --program-headers --dynamic $binary
    if($LASTEXITCODE -ne 0){ throw "Could not inspect Android library for $abi" }
    $segments = @($elf | Where-Object { $_ -match '^\s*LOAD\s+' })
    if(-not $segments){ throw "ELF has no load segments for $abi" }
    foreach($segment in $segments){
        $alignment = ($segment.Trim() -split '\s+')[-1]
        if([Convert]::ToInt64($alignment.Substring(2), 16) -lt 16384){
            throw "ELF load segment is not 16 KiB aligned for $abi"
        }
    }
    $allowed = @('libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so')
    foreach($line in $elf){
        if($line -match '\(NEEDED\).*\[([^\]]+)\]' -and $Matches[1] -notin $allowed){
            throw "Android library for $abi requires an unpackaged dependency: $($Matches[1])"
        }
    }
    $exports = & $readelf --wide --dyn-syms $binary
    if($LASTEXITCODE -ne 0 -or !($exports -match 'Java_dev_turbodl_nativebridge_NativeBridge_nCapabilities$')){
        throw "Android library is missing JNI exports for $abi"
    }
    $packaged = Join-Path $destination 'libturbodl_engine.so'
    Copy-Item -LiteralPath $binary -Destination $packaged
    Get-FileHash $packaged
}
