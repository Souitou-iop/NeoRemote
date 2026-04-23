$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$buildDir = Join-Path $repoRoot "Windows\build"
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $buildDir "obj\tests") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $buildDir "obj\app") | Out-Null

$vsDevCmd = "C:\Program Files\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat"
if (-not (Test-Path $vsDevCmd)) {
    throw "Visual Studio developer command prompt not found at $vsDevCmd"
}

$coreSources = @(
    "Windows\src\NeoRemote.Core\src\Protocol.cpp",
    "Windows\src\NeoRemote.Core\src\JsonMessageStreamDecoder.cpp",
    "Windows\src\NeoRemote.Core\src\MouseEventPlanner.cpp",
    "Windows\src\NeoRemote.Core\src\DesktopRemoteService.cpp"
)

$windowsSources = @(
    "Windows\src\NeoRemote.Windows\src\TcpRemoteServer.cpp",
    "Windows\src\NeoRemote.Windows\src\UdpDiscoveryResponder.cpp",
    "Windows\src\NeoRemote.Windows\src\MdnsPublisher.cpp",
    "Windows\src\NeoRemote.Windows\src\Win32InputInjector.cpp",
    "Windows\src\NeoRemote.Windows\src\TrayIcon.cpp"
)

$testSources = $coreSources + @("Windows\tests\NeoRemote.Core.Tests\main.cpp")
$appSources = $coreSources + $windowsSources + @("Windows\src\NeoRemote.Win32App\main.cpp")

$includeArgs = "/IWindows\src\NeoRemote.Core\include /IWindows\src\NeoRemote.Windows\include"
$resourceOutput = "Windows\build\NeoRemote.Win32App.res"
$resourceCommand = "rc /nologo /fo $resourceOutput Windows\src\NeoRemote.Win32App\resources\NeoRemote.Win32App.rc"
$libs = "Ws2_32.lib Dnsapi.lib Shell32.lib User32.lib Gdi32.lib Dwmapi.lib Advapi32.lib Winmm.lib"
$signTool = "C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe"
$cert = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert |
    Where-Object { $_.Subject -eq "CN=NeoRemote Local Development" } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1

$commonFlags = "/nologo /std:c++20 /EHsc /utf-8 /DUNICODE /D_UNICODE"
$testCommand = "cl $commonFlags /FoWindows\build\obj\tests\ $includeArgs $($testSources -join ' ') /Fe:Windows\build\NeoRemote.Core.Tests.exe"
$appCommand = "cl $commonFlags /FoWindows\build\obj\app\ $includeArgs $($appSources -join ' ') $resourceOutput /Fe:Windows\build\NeoRemote.WindowsReceiver.exe /link /SUBSYSTEM:WINDOWS $libs"

Push-Location $repoRoot
try {
    cmd.exe /c "`"$vsDevCmd`" -arch=x64 && $testCommand && Windows\build\NeoRemote.Core.Tests.exe && $resourceCommand && $appCommand"
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

if ($cert -and (Test-Path $signTool)) {
    & $signTool sign /fd SHA256 /sha1 $cert.Thumbprint (Join-Path $repoRoot "Windows\build\NeoRemote.WindowsReceiver.exe")
    if ($LASTEXITCODE -ne 0) {
        throw "Signing failed with exit code $LASTEXITCODE"
    }
}

Write-Host "Built Windows\build\NeoRemote.WindowsReceiver.exe"
