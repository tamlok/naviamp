#Requires -Version 7.0
<#
.SYNOPSIS
Builds and launches the current checkout's Windows development app.
.DESCRIPTION
Requires Windows, JDK 21, Android SDK platform 37, CMake 3.22+, and a Windows
C++ toolchain. Uses the Gradle wrapper and the existing development data profile.
Does not update Git, clean build caches, install the app, or stop running apps.
.EXAMPLE
pwsh -NoProfile -File ./scripts/windows-dev.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

if (-not $IsWindows) {
    throw 'This script builds and runs the Windows app; run it on Windows.'
}

function ConvertTo-ApplicationPath([string]$Path) {
    if ($Path.StartsWith('\\?\')) {
        $Path = $Path.Substring(4)
    }
    [IO.Path]::GetFullPath($Path)
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$applicationName = (Get-Content -LiteralPath (Join-Path $repositoryRoot 'APPLICATION_NAME') -Raw).Trim()
$applicationDirectory = Join-Path $repositoryRoot "build/local-test/$applicationName"
$executable = ConvertTo-ApplicationPath (Join-Path $applicationDirectory "$applicationName.exe")
$buildExecutable = ConvertTo-ApplicationPath (Join-Path $repositoryRoot "apps/desktop/build/compose/binaries/main/app/$applicationName/$applicationName.exe")

# Both images must be unlocked before Gradle rebuilds or replaces their files.
foreach ($process in Get-Process -Name $applicationName -ErrorAction SilentlyContinue) {
    $processPath = $process.Path
    if (-not $processPath) {
        throw "Cannot identify the executable path of $applicationName (PID $($process.Id)). Close it before rebuilding."
    }
    $processPath = ConvertTo-ApplicationPath $processPath
    if ($processPath -ieq $executable -or $processPath -ieq $buildExecutable) {
        throw "Close the local $applicationName app before rebuilding (PID $($process.Id), $processPath)."
    }
}

Push-Location -LiteralPath $repositoryRoot
try {
    $visualStudioPath = $env:VSINSTALLDIR
    if (-not $visualStudioPath) {
        $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
        if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
            throw 'Install Visual Studio Build Tools with MSVC and the Windows SDK; vswhere.exe was not found.'
        }
        $visualStudioPath = & $vswhere -latest -products '*' `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($visualStudioPath)) {
            throw 'No Visual Studio installation with MSVC x64 tools was found. Install Build Tools with MSVC and the Windows SDK.'
        }
    }
    $developerShellModule = Join-Path $visualStudioPath 'Common7/Tools/Microsoft.VisualStudio.DevShell.dll'
    if (-not (Test-Path -LiteralPath $developerShellModule -PathType Leaf)) {
        throw "Visual Studio Developer PowerShell is missing: $developerShellModule. Repair the Build Tools installation."
    }
    Write-Host 'Initializing the Windows x64 compiler and SDK environment...'
    Import-Module $developerShellModule
    Enter-VsDevShell -VsInstallPath $visualStudioPath -Arch amd64 -HostArch amd64 -SkipAutomaticLocation

    Write-Host 'Building and staging the current checkout for Windows x64...'
    & .\gradlew.bat `
        '-Pkotlin.native.enableKlibsCrossCompilation=false' `
        '-Pnaviamp.bass.platform=windows-x64' `
        :apps:desktop:stageLocalTestApp
    if ($LASTEXITCODE -ne 0) {
        throw "Windows development build failed with exit code $LASTEXITCODE. $applicationName was not launched."
    }
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "The build did not produce the staged executable: $executable"
    }

    $applicationProcess = Start-Process `
        -FilePath $executable `
        -WorkingDirectory $applicationDirectory `
        -PassThru
    Write-Host "Launched the Windows development app (PID $($applicationProcess.Id)): $executable"
} finally {
    Pop-Location
}
