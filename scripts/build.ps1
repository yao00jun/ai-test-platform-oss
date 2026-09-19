#requires -Version 7.4
param([switch]$SkipTests, [switch]$SkipInstall, [string]$MavenSettings = '', [string]$OutputDirectory = '', [int]$Forks = 3)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $projectRoot 'frontend'
$nodeVersion = (& node -p 'process.versions.node').Trim()
if ($LASTEXITCODE -ne 0 -or [int]$nodeVersion.Split('.')[0] -lt 24) { throw 'Node.js 24 or later is required to build the frontend.' }

# Resolve the package manager pinned in frontend/package.json (pnpm@<x>). Prefer the locally
# generated corepack shim under .runtime/pnpm-shim (offline, no download); fall back to
# `corepack pnpm`, which materialises the pinned version on demand. This keeps the release
# build independent of whichever pnpm happens to be on PATH (handoff doc, section 6.1).
$shimDir = Join-Path $projectRoot '.runtime/pnpm-shim'
$pnpmExe = 'pnpm'; $pnpmLead = @()
if (Test-Path -LiteralPath $shimDir) {
    $env:PATH = "$shimDir;" + $env:PATH
} elseif (Get-Command corepack -ErrorAction SilentlyContinue) {
    $pnpmExe = 'corepack'; $pnpmLead = @('pnpm')
}
function Invoke-Pnpm { param([Parameter(ValueFromRemainingArguments)] [string[]]$PnpmArgs); & $pnpmExe @pnpmLead @PnpmArgs }

Push-Location $frontend
try {
    $package = Get-Content -Raw -LiteralPath (Join-Path $frontend 'package.json') | ConvertFrom-Json
    $pnpmVersion = (Invoke-Pnpm --version).Trim()
    if ($LASTEXITCODE -ne 0 -or ('pnpm@' + $pnpmVersion) -ne $package.packageManager) { throw "Install the package manager pinned in frontend/package.json: $($package.packageManager)" }
    if (-not $SkipInstall) {
        Invoke-Pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    Invoke-Pnpm lint
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    if (-not $SkipTests) {
        Invoke-Pnpm test:unit
        if ($LASTEXITCODE -ne 0) { throw 'Frontend unit tests failed.' }
    }
    Invoke-Pnpm build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend type checking or build failed.' }
} finally { Pop-Location }
# Release builds run the full integration suite: the default failsafe configuration skips
# @Tag("slow") classes for day-to-day verify, the nightly profile puts them back.
# -Forks: parallel failsafe JVMs (each with its own test schema); pass 1 on a mechanical-disk MySQL data directory.
if (-not $SkipTests) {
    # Integration tests share one persistent schema; start every release build from an empty one.
    & (Join-Path $PSScriptRoot 'reset-test-databases.ps1') -Forks $Forks
    if ($LASTEXITCODE -ne 0) { throw 'Could not reset the integration-test databases.' }
}
$mavenArguments = @('-B','-ntp','-Pdistribution,nightly','clean','verify',"-Daitest.it.forks=$Forks")
if ($MavenSettings) { $mavenArguments += @('-s', [IO.Path]::GetFullPath($MavenSettings)) }
if ($SkipTests) { $mavenArguments += '-DskipTests' }
& (Join-Path $PSScriptRoot 'maven.ps1') @mavenArguments
if ($LASTEXITCODE -ne 0) { throw 'Backend verification or packaging failed.' }

[xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'backend/pom.xml')
$version = [string]$pom.project.version
$jar = Join-Path $projectRoot "backend/target/ai-test-platform-$version.jar"
$jarArchive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($entry in @('BOOT-INF/classes/static/index.html','BOOT-INF/classes/com/aitest/AiTestApplication.class')) {
        if ($null -eq $jarArchive.GetEntry($entry)) { throw "Distribution JAR is incomplete: $entry" }
    }
    if (@($jarArchive.Entries | Where-Object FullName -Like 'BOOT-INF/classes/db/migration/V*.sql').Count -lt 23) { throw 'Distribution JAR is missing database migrations.' }
} finally { $jarArchive.Dispose() }

$revision = 'source'
$dirty = $null
if (Test-Path -LiteralPath (Join-Path $projectRoot '.git')) {
    $revision = (& git -C $projectRoot rev-parse --short=12 HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not identify the source revision.' }
    $dirty = -not [string]::IsNullOrWhiteSpace((& git -C $projectRoot status --porcelain | Out-String))
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'artifacts/releases' }
$releaseName = "ai-test-platform-$version-" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + "-$revision"
$releaseRoot = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $releaseName
if (Test-Path -LiteralPath $releaseRoot) { throw 'The release destination already exists; no files were overwritten.' }
$null = New-Item -ItemType Directory -Path $releaseRoot
[IO.File]::Copy($jar, (Join-Path $releaseRoot 'app.jar'), $false)
[IO.File]::Copy((Join-Path $projectRoot 'deploy/config.example.json'), (Join-Path $releaseRoot 'config.example.json'), $false)
# The release carries user-facing documentation only. Acceptance evidence,
# implementation plans and superseded design drafts stay in the source
# repository; they are still part of source.zip, the corresponding-source archive.
# The public source repository ships without docs/; the release then carries the README only.
$docsRoot = Join-Path $projectRoot 'docs'
if (Test-Path -LiteralPath $docsRoot -PathType Container) {
    $releaseDocs = Join-Path $releaseRoot 'docs'
    $null = New-Item -ItemType Directory -Path $releaseDocs
    $internalDocs = @('roadmap.md','codex-implementation-prompt.md','session-handoff-2026-09-18.md')
    foreach ($file in Get-ChildItem -LiteralPath $docsRoot -File) {
        if ($file.Extension -in @('.md','.sql') -and $internalDocs -notcontains $file.Name) {
            [IO.File]::Copy($file.FullName, (Join-Path $releaseDocs $file.Name), $false)
        }
    }
    if (Test-Path -LiteralPath (Join-Path $docsRoot 'prompts')) { Copy-Item -LiteralPath (Join-Path $docsRoot 'prompts') -Destination (Join-Path $releaseDocs 'prompts') -Recurse }
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'licenses') -Destination (Join-Path $releaseRoot 'licenses') -Recurse
$null = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'scripts'),(Join-Path $releaseRoot 'database')
foreach ($script in @('start.ps1','stop.ps1','check.ps1','install-browsers.ps1','backup.ps1','restore.ps1','operations-common.ps1')) {
    [IO.File]::Copy((Join-Path $PSScriptRoot $script), (Join-Path $releaseRoot "scripts/$script"), $false)
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/src/main/resources/db/migration') -Destination (Join-Path $releaseRoot 'database/migration') -Recurse
foreach ($file in @('README.md','NOTICE.md')) { [IO.File]::Copy((Join-Path $projectRoot $file), (Join-Path $releaseRoot $file), $false) }

# Corresponding source is selected explicitly; runtime data, target and node_modules
# are never traversed. The archive is usable without a .git directory.
$sourceFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($directory in @('backend/src','backend/.mvn','frontend/src','frontend/public','frontend/tests','scripts','docs','licenses','deploy')) {
    $sourceDirectory = Join-Path $projectRoot $directory
    if (Test-Path -LiteralPath $sourceDirectory) {
        foreach ($file in Get-ChildItem -LiteralPath $sourceDirectory -Force -Recurse -File) { $null = $sourceFiles.Add($file.FullName) }
    }
}
foreach ($relative in @('.gitignore','README.md','NOTICE.md','backend-pom-template.xml','frontend-package-template.json',
        'backend/pom.xml','backend/mvnw','backend/mvnw.cmd','frontend/.gitignore','frontend/README.md','frontend/THIRD_PARTY_NOTICES.md',
        'frontend/package.json','frontend/pnpm-lock.yaml','frontend/index.html','frontend/tsconfig.json','frontend/vite.config.ts',
        'frontend/vitest.config.ts','frontend/eslint.config.js','frontend/playwright.config.ts','frontend/playwright.ai.config.ts','frontend/playwright.auth.config.ts')) {
    $file = Join-Path $projectRoot $relative
    if (Test-Path -LiteralPath $file -PathType Leaf) { $null = $sourceFiles.Add($file) }
}
$sourceArchive = [IO.Compression.ZipFile]::Open((Join-Path $releaseRoot 'source.zip'), [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($sourceFiles | Sort-Object)) {
        $entry = Get-Item -LiteralPath $file -Force
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Source archive does not accept linked files.' }
        $relative = [IO.Path]::GetRelativePath($projectRoot, $file).Replace('\','/')
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($sourceArchive, $file, $relative, [IO.Compression.CompressionLevel]::Optimal)
    }
} finally { $sourceArchive.Dispose() }
$metadata = @{version=$version; sourceRevision=$revision; uncommittedSource=$dirty; builtAt=[DateTime]::UtcNow.ToString('O'); node=$nodeVersion; pnpm=$pnpmVersion; testsSkipped=[bool]$SkipTests}
[IO.File]::WriteAllText((Join-Path $releaseRoot 'release.json'), ($metadata | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$checksums = Get-ChildItem -LiteralPath $releaseRoot -Force -Recurse -File | Sort-Object FullName | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetRelativePath($releaseRoot, $_.FullName).Replace('\','/')
}
[IO.File]::WriteAllLines((Join-Path $releaseRoot 'SHA256SUMS'), [string[]]$checksums, [Text.UTF8Encoding]::new($false))
[IO.Compression.ZipFile]::CreateFromDirectory($releaseRoot, "$releaseRoot.zip", [IO.Compression.CompressionLevel]::Optimal, $false)
Write-Output "Release directory: $releaseRoot"
Write-Output "Release archive: $releaseRoot.zip"
Write-Output "Archive SHA-256: $((Get-FileHash -LiteralPath "$releaseRoot.zip" -Algorithm SHA256).Hash)"
