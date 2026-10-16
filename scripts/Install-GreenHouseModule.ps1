[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Destination
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
$destinationRoot = (Resolve-Path -LiteralPath $Destination).Path
$backend = Join-Path $destinationRoot 'backend'
$module = Join-Path $backend 'modules/slip-image-core'
$settingsPath = Join-Path $backend 'settings.gradle.kts'
$buildPath = Join-Path $backend 'build.gradle.kts'
$dockerPath = Join-Path $backend 'Dockerfile'

# Validate every patch before writing. Existing modules are never overwritten.
if (Test-Path -LiteralPath $module) {
    throw "Module already exists: $module. Review/update it manually; no files were changed."
}
foreach ($path in @($settingsPath, $buildPath, $dockerPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected Green House build file is missing: $path"
    }
}
$settings = [IO.File]::ReadAllText($settingsPath)
$build = [IO.File]::ReadAllText($buildPath)
$docker = [IO.File]::ReadAllText($dockerPath)
if ($settings -match 'slip-image-core' -or $build -match 'slip-image-core') {
    throw 'Existing slip-image-core configuration found. Review it manually; no files were changed.'
}
if ([regex]::Matches($build, '(?m)^dependencies\s*\{').Count -ne 1) {
    throw 'Expected one top-level dependencies block; no files were changed.'
}
if ([regex]::Matches($docker, '(?m)^COPY src src\r?$').Count -ne 1) {
    throw 'Dockerfile differs from the verified layout; no files were changed.'
}
if ($docker -match '(?m)^COPY modules\s') {
    throw 'Dockerfile already copies modules; review it manually.'
}
if ($settings -match 'FAIL_ON_PROJECT_REPOS') {
    throw 'Central repository policy changed. Follow the manual integration guide.'
}

$settingsNew = $settings.TrimEnd() + "`n`n" + 'include(":slip-image-core")' + "`n"
$settingsNew += 'project(":slip-image-core").projectDir = file("modules/slip-image-core")' + "`n"
$buildNew = [regex]::Replace($build, '(?m)^dependencies\s*\{',
    ("dependencies {`n`t" + 'implementation(project(":slip-image-core"))'))
# The host's E2E task otherwise resolves project dependencies during configuration,
# before the new library's variants have been configured.
$earlyClasspath = 'systemProperty("greenhouse.cli.runtime-classpath", sourceSets["main"].runtimeClasspath.asPath)'
$buildNew = $buildNew.Replace($earlyClasspath, ("doFirst {`n`t`t" + $earlyClasspath + "`n`t}"))
$dockerNew = [regex]::Replace($docker, '(?m)^COPY src src\r?$',
    "COPY modules modules`nCOPY src src")

New-Item -ItemType Directory -Path $module | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceRoot 'slip-image-core/src') -Destination $module -Recurse
Copy-Item -LiteralPath (Join-Path $sourceRoot 'slip-image-core/build.gradle.kts') -Destination $module
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($settingsPath, $settingsNew, $utf8)
[IO.File]::WriteAllText($buildPath, $buildNew, $utf8)
[IO.File]::WriteAllText($dockerPath, $dockerNew, $utf8)

Write-Host "Installed source module: $module"
Write-Host 'Review git diff in Green House, then run backend/gradlew :slip-image-core:test check bootJar.'
Write-Host 'Register the singleton Bean when connecting the Sales image use case; see docs/MIGRATION_GREEN_HOUSE.md.'
Write-Host 'No commit or push was performed.'
