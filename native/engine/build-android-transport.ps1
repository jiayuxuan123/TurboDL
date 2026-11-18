param(
    [Parameter(Mandatory = $true)][string]$Ndk,
    [Parameter(Mandatory = $true)][string]$Cmake,
    [Parameter(Mandatory = $true)][string]$SourceCache,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')]
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'),
    [ValidateRange(1, 32)][int]$Jobs = 4
)

$ErrorActionPreference = 'Stop'
$toolchain = Join-Path $Ndk 'build/cmake/android.toolchain.cmake'
$ninja = Join-Path (Split-Path -Parent $Cmake) 'ninja.exe'

function Build-Dependency($Name, $Source, $Options) {
    $build = Join-Path $OutputRoot "build/$abi/$Name"
    & $Cmake -S (Join-Path $SourceCache $Source) -B $build -G Ninja @common @Options
    if ($LASTEXITCODE -ne 0) { throw "$Name configuration failed for $abi" }
    & $Cmake --build $build --target install --parallel $Jobs
    if ($LASTEXITCODE -ne 0) { throw "$Name build failed for $abi" }
}

foreach ($abi in $Abis) {
    $prefix = [IO.Path]::GetFullPath((Join-Path $OutputRoot "prefix/$abi"))
    $common = @(
        "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=$abi", '-DANDROID_PLATFORM=android-24',
        '-DANDROID_STL=c++_static', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
        "-DCMAKE_INSTALL_PREFIX=$prefix", "-DCMAKE_PREFIX_PATH=$prefix",
        "-DCMAKE_FIND_ROOT_PATH=$prefix", "-DOPENSSL_ROOT_DIR=$prefix",
        '-DOPENSSL_USE_STATIC_LIBS=ON', '-DCMAKE_INSTALL_LIBDIR=lib',
        '-DBUILD_TESTING=OFF', '-DCMAKE_BUILD_TYPE=Release',
        '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DBUILD_SHARED_LIBS=OFF'
    )
    Build-Dependency 'libressl' 'libressl-4.4.1' @(
        '-DLIBRESSL_APPS=OFF', '-DLIBRESSL_TESTS=OFF', '-DENABLE_ASM=OFF'
    )
    Build-Dependency 'nghttp2' 'transport/nghttp2-1.71.0' @(
        '-DENABLE_LIB_ONLY=ON', '-DBUILD_STATIC_LIBS=ON'
    )
    $quicOptions = @('-DENABLE_LIB_ONLY=ON', '-DENABLE_SHARED_LIB=OFF', '-DENABLE_STATIC_LIB=ON')
    Build-Dependency 'nghttp3' 'transport/nghttp3-1.19.0' $quicOptions
    Build-Dependency 'ngtcp2' 'transport/ngtcp2-1.26.0' ($quicOptions + '-DENABLE_OPENSSL=ON')
    Build-Dependency 'libssh2' 'transport/libssh2-1.11.1' @(
        '-DCRYPTO_BACKEND=OpenSSL', '-DBUILD_EXAMPLES=OFF', '-DBUILD_STATIC_LIBS=ON'
    )
    Build-Dependency 'curl' 'curl-8.23.0' @(
        '-DBUILD_CURL_EXE=OFF', '-DBUILD_STATIC_LIBS=ON', '-DBUILD_EXAMPLES=OFF',
        '-DBUILD_LIBCURL_DOCS=OFF', '-DBUILD_MISC_DOCS=OFF',
        '-DCURL_USE_OPENSSL=ON', '-DCURL_USE_LIBSSH2=ON',
        '-DNGHTTP2_USE_STATIC_LIBS=ON', '-DNGHTTP3_USE_STATIC_LIBS=ON',
        '-DNGTCP2_USE_STATIC_LIBS=ON', '-DLIBSSH2_USE_STATIC_LIBS=ON',
        '-DCURL_USE_PKGCONFIG=OFF', '-DCURL_USE_CMAKECONFIG=OFF',
        '-DUSE_NGHTTP2=ON', '-DUSE_NGTCP2=ON', '-DCURL_USE_LIBPSL=OFF',
        '-DUSE_LIBIDN2=OFF', '-DCURL_DISABLE_LDAP=ON', '-DCURL_DISABLE_LDAPS=ON',
        '-DCURL_DISABLE_HTTPSIG=ON'
    )

    # Verify the exported feature list from this target's curl configuration.
    $config = Join-Path $prefix 'lib/cmake/CURL/CURLConfig.cmake'
    $contents = Get-Content -LiteralPath $config -Raw
    foreach ($component in @('HTTP2', 'HTTP3', 'HTTPS', 'FTP', 'SFTP')) {
        if ($contents -notmatch "(?<![A-Za-z0-9_])${component}(?![A-Za-z0-9_])") {
            throw "curl $abi is missing $component"
        }
    }
    Write-Output "curl ${abi}: HTTP/2, HTTP/3, HTTPS, FTP, SFTP"
}
