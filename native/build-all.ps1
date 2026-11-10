param(
    [string]$Zig = "zig"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$dist = Join-Path $root "dist"
$targets = @(
    @("x86_64-windows-gnu", "windows-x86_64"),
    @("aarch64-windows-gnu", "windows-arm64"),
    @("x86_64-linux-gnu", "linux-x86_64-glibc"),
    @("x86_64-linux-musl", "linux-x86_64-musl"),
    @("aarch64-linux-gnu", "linux-arm64-glibc"),
    @("aarch64-linux-musl", "linux-arm64-musl"),
    @("x86_64-macos-none", "macos-x86_64"),
    @("aarch64-macos-none", "macos-arm64")
)

foreach ($component in @("launcher", "dataplane")) {
    Push-Location (Join-Path $root $component)
    try {
        foreach ($target in $targets) {
            $arg = "-Dtarget=" + $target[0]
            & $Zig build $arg -Doptimize=ReleaseFast -j2 --prefix (Join-Path $dist "$component/$($target[1])")
            if ($LASTEXITCODE -ne 0) { throw "$component failed for $($target[0])" }
            Write-Output "$component/$($target[1]) compiled"
        }
    } finally {
        Pop-Location
    }
}

Write-Output "native artifacts: $dist"
