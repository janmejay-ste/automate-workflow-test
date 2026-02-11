$ErrorActionPreference = "Stop"
$toolsDir = Join-Path $PSScriptRoot "tools"

# Locate tools
$jdkDir = Get-ChildItem -Path $toolsDir -Directory -Filter "jdk-*" | Select-Object -First 1
$mavenDir = Get-ChildItem -Path $toolsDir -Directory -Filter "apache-maven-*" | Select-Object -First 1

if (-not $jdkDir) { Write-Error "JDK not found in tools/"; exit 1 }
if (-not $mavenDir) { Write-Error "Maven not found in tools/"; exit 1 }

# Configure Env
$env:JAVA_HOME = $jdkDir.FullName
$env:MAVEN_HOME = $mavenDir.FullName
$env:PATH = "$($mavenDir.FullName)\bin;$($jdkDir.FullName)\bin;$env:PATH"

# Run Maven
Write-Host "Running Maven with:"
Write-Host "  JAVA_HOME: $env:JAVA_HOME"
Write-Host "  MAVEN_HOME: $env:MAVEN_HOME"
Write-Host "  Command: mvn $args"
Write-Host "--------------------------------------------------"

& mvn $args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
