$toolsDir = Join-Path $PSScriptRoot "tools"
$jdkDir = Get-ChildItem -Path $toolsDir -Directory -Filter "jdk-*" | Select-Object -First 1
$mavenDir = Get-ChildItem -Path $toolsDir -Directory -Filter "apache-maven-*" | Select-Object -First 1

if (-not $jdkDir -or -not $mavenDir) {
    Write-Error "Could not find JDK or Maven in tools directory"
    exit 1
}

$jdkBin = "$($jdkDir.FullName)\bin"
$mavenBin = "$($mavenDir.FullName)\bin"

# METHOD 1: Update registry directly for User PATH
$regKey = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("Environment", $true)
$currentPath = $regKey.GetValue("Path", "", [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)

$newPath = $currentPath
if ($newPath -notmatch [regex]::Escape($jdkBin)) {
    $newPath = "$jdkBin;" + $newPath
}
if ($newPath -notmatch [regex]::Escape($mavenBin)) {
    $newPath = "$mavenBin;" + $newPath
}

$regKey.SetValue("Path", $newPath, [Microsoft.Win32.RegistryValueKind]::ExpandString)
$regKey.SetValue("JAVA_HOME", $jdkDir.FullName, [Microsoft.Win32.RegistryValueKind]::String)
$regKey.SetValue("MAVEN_HOME", $mavenDir.FullName, [Microsoft.Win32.RegistryValueKind]::String)
$regKey.Close()

# METHOD 2: Machine level variables mapping
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir.FullName, "User")
[Environment]::SetEnvironmentVariable("MAVEN_HOME", $mavenDir.FullName, "User")

# Broadcast the environment change to the Windows shell
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class EnvRefresh {
    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
}
"@
$HWND_BROADCAST = [IntPtr]0xffff
$WM_SETTINGCHANGE = 0x001A
$SMTO_ABORTIFHUNG = 0x0002
[UIntPtr]$result = [UIntPtr]::Zero

[EnvRefresh]::SendMessageTimeout($HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, "Environment", $SMTO_ABORTIFHUNG, 5000, [ref]$result) | Out-Null

Write-Host "PATH updated in Registry. Please completely restart VSCode or open a brand-new untabbed Command Prompt."
