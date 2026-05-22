param(
    [string]$OlcrtcRef = "main",
    [string]$VkTurnRef = "main",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([string[]]$Arguments, [string]$WorkDir = $PWD)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed in $WorkDir"
    }
}

function Sync-Directory {
    param(
        [string]$Source,
        [string]$Destination,
        [string[]]$ExcludeDirs = @(".git")
    )
    if (!(Test-Path $Destination)) {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    }
    $args = @($Source, $Destination, "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
    if ($ExcludeDirs.Count -gt 0) {
        $args += "/XD"
        $args += $ExcludeDirs
    }
    & robocopy @args | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed from $Source to $Destination with exit code $LASTEXITCODE"
    }
}

$coreDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tempRoot = Join-Path $coreDir ".upstream-tmp"
$adapterRoot = Join-Path $tempRoot "adapters"
$olcrtcDest = Join-Path $coreDir "olcrtc_local"
$vkDest = Join-Path $coreDir "vk_turn_proxy_local"

Remove-Item -Recurse -Force -LiteralPath $tempRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tempRoot, $adapterRoot | Out-Null

Write-Host "Updating olcRTC from upstream..."
$olcrtcClone = Join-Path $tempRoot "olcrtc"
Invoke-Git @("clone", "--depth", "1", "--branch", $OlcrtcRef, "https://github.com/openlibrecommunity/olcrtc.git", $olcrtcClone)
Sync-Directory $olcrtcClone $olcrtcDest

Write-Host "Updating vk-turn-proxy from upstream..."
$vkAdapter = Join-Path $adapterRoot "vk-turn-proxy"
$adapterFiles = @(
    "client\main.go",
    "client\wrap.go",
    "client\manual_captcha.go",
    "client\ish_listener_other.go",
    "client\ish_listener_linux_386.go",
    "client\main_test.go",
    "client\manual_captcha_test.go",
    "client\wrap_test.go",
    "mobile\mobile.go"
)
foreach ($relative in $adapterFiles) {
    $source = Join-Path $vkDest $relative
    if (Test-Path $source) {
        $target = Join-Path $vkAdapter $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $source -Destination $target -Force
    }
}

$vkClone = Join-Path $tempRoot "vk-turn-proxy"
Invoke-Git @("clone", "--depth", "1", "--branch", $VkTurnRef, "https://github.com/netzgiest/vk-turn-proxy.git", $vkClone)
Sync-Directory $vkClone $vkDest

foreach ($relative in $adapterFiles) {
    $source = Join-Path $vkAdapter $relative
    if (Test-Path $source) {
        $target = Join-Path $vkDest $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $source -Destination $target -Force
    }
}

Remove-Item -Recurse -Force -LiteralPath $tempRoot -ErrorAction SilentlyContinue

Write-Host "Upstreams are updated. Android/gomobile adapter files were preserved."
Write-Host "Review the diff, then rebuild libsagernetcore.aar with build.bat."

if (!$NoBuild) {
    & (Join-Path $coreDir "build.bat")
    if ($LASTEXITCODE -ne 0) {
        throw "build.bat failed"
    }
}
