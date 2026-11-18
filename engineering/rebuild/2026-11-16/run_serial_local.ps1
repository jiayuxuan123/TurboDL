param(
    [Parameter(Mandatory=$true)][string]$EvidenceRoot,
    [string]$ComparisonScratchRoot,
    [string]$ReuseGate,
    [switch]$AllowGateReuse
)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$cacheRoot='D:\CodexResearch\turbodl-rebuild-20261116'
$scratchRoot=Join-Path $cacheRoot 'test-runs'
if(-not $ComparisonScratchRoot){$ComparisonScratchRoot=$scratchRoot}
$pythonExe=(Get-Command python.exe).Source
if(Test-Path -LiteralPath $EvidenceRoot){throw 'Use a fresh evidence directory'}
New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null
$evidenceResolved=(Resolve-Path -LiteralPath $EvidenceRoot).Path
Set-Location -LiteralPath $repoRoot
$buildRoot=Join-Path $cacheRoot 'build-release'
$frozenBuild=Join-Path $evidenceResolved 'build'
New-Item -ItemType Directory -Path $frozenBuild | Out-Null
foreach($artifact in (Get-ChildItem -LiteralPath $buildRoot -File | Where-Object Extension -in '.dll','.exe')){
    $expected=(Get-FileHash -LiteralPath $artifact.FullName).Hash
    $copy=Join-Path $frozenBuild $artifact.Name
    Copy-Item -LiteralPath $artifact.FullName -Destination $copy
    if((Get-FileHash -LiteralPath $copy).Hash -ne $expected -or
       (Get-FileHash -LiteralPath $artifact.FullName).Hash -ne $expected){
        throw "Build changed while freezing $($artifact.Name)"
    }
}
$library=Join-Path $frozenBuild 'turbodl_engine.dll'
$currentHash=(Get-FileHash -LiteralPath $library).Hash.ToLowerInvariant()

$identityText=(& $pythonExe (Join-Path $PSScriptRoot 'run_local_gate.py') --print-identity 2>&1 | Out-String)
if($LASTEXITCODE -ne 0){throw "Unable to calculate gate source identity: $identityText"}
$identity=$identityText | ConvertFrom-Json
if(-not $identity.sha256){throw 'Gate source identity did not include an aggregate SHA-256'}

if($ReuseGate){
    if(-not $AllowGateReuse){
        throw 'Gate reuse is disabled by default; pass -AllowGateReuse after confirming the matching source identity'
    }
    $reusePath=(Resolve-Path -LiteralPath $ReuseGate).Path
    $gate=Get-Content -LiteralPath $reusePath -Raw | ConvertFrom-Json
    if(-not $gate.success -or $gate.status -ne 'finished' -or $gate.skipped -ne 0 -or
       $gate.scope -ne 'full' -or $gate.inputs_unchanged -ne $true){
        throw 'Prior gate is not a successful, fully executed gate'
    }
    if($gate.source_identity_sha256 -ne $identity.sha256){
        throw 'Prior gate source identity does not match tests, fixtures, or adapters'
    }
    if($gate.cleanup_ok -ne $true -or $gate.scratch_cleaned -ne $true){
        throw 'Prior gate did not prove clean process and scratch teardown'
    }
    Copy-Item -LiteralPath $reusePath -Destination "$evidenceResolved\gate.json"
    @{
        reused_gate=$reusePath
        source_identity_sha256=$identity.sha256
        library_sha256=$currentHash
    } | ConvertTo-Json | Set-Content -LiteralPath "$evidenceResolved\gate-source.json"
}else{
    & $pythonExe (Join-Path $PSScriptRoot 'run_local_gate.py') `
        --cache $cacheRoot `
        --build $frozenBuild `
        --scratch-root $scratchRoot `
        --result "$evidenceResolved\gate.json" *> "$evidenceResolved\gate.log"
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    $gate=Get-Content -LiteralPath "$evidenceResolved\gate.json" -Raw | ConvertFrom-Json
    if($gate.source_identity_sha256 -ne $identity.sha256){
        throw 'Executed gate source identity does not match the current harness'
    }
    if($gate.cleanup_ok -ne $true -or $gate.scratch_cleaned -ne $true){
        throw 'Executed gate did not prove clean process and scratch teardown'
    }
}

if($gate.library_sha256 -ne $currentHash -or -not $gate.binary_hashes){
    throw 'Gate does not validate the frozen build'
}
foreach($entry in $gate.binary_hashes.PSObject.Properties){
    if((Get-FileHash -LiteralPath (Join-Path $frozenBuild $entry.Name)).Hash -ne $entry.Value){
        throw "Build artifact differs from the gate: $($entry.Name)"
    }
}

& $pythonExe (Join-Path $PSScriptRoot 'compare_local_engines.py') `
    --output "$evidenceResolved\comparison" `
    --scratch-root $ComparisonScratchRoot `
    --native-library $library `
    --warmups 1 `
    --pairs 5 `
    --max-pairs 9 `
    --engines native sdk wenxi gopeed aria2 curl `
    --cases single unlimited paced16 paced latency tail straggler `
    *> "$evidenceResolved\comparison.log"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}

$manifest=Get-Content -LiteralPath "$evidenceResolved\comparison\manifest.json" -Raw | ConvertFrom-Json
if($manifest.artifacts.native.sha256 -ne $gate.library_sha256){
    throw 'Comparison used a different library than the gate'
}
$cleanupPath="$evidenceResolved\comparison\cleanup.json"
if(-not (Test-Path -LiteralPath $cleanupPath)){throw 'Comparison did not emit a cleanup record'}
$cleanup=Get-Content -LiteralPath $cleanupPath -Raw | ConvertFrom-Json
if($cleanup.ok -ne $true -or $cleanup.scratch_cleaned -ne $true){
    throw "Comparison cleanup failed: $($cleanup.errors -join '; ')"
}

