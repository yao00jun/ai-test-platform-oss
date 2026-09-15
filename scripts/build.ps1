#requires -Version 7.4
param([switch]$SkipTests, [switch]$SkipInstall, [string]$MavenSettings = '', [string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $projectRoot 'frontend'
$nodeVersion = (& node -p 'process.versions.node').Trim()
if ($LASTEXITCODE -ne 0 -or [int]$nodeVersion.Split('.')[0] -lt 24) { throw 'Node.js 24 or later is required to build the frontend.' }
$pnpmVersion = (& pnpm --version).Trim()
$package = Get-Content -Raw -LiteralPath (Join-Path $frontend 'package.json') | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or ('pnpm@' + $pnpmVersion) -ne $package.packageManager) { throw "Install the package manager pinned in frontend/package.json: $($package.packageManager)" }
Push-Location $frontend
try {
    if (-not $SkipInstall) {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    & pnpm lint
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed.' }
    if (-not $SkipTests) {
        & pnpm test:unit
        if ($LASTEXITCODE -ne 0) { throw 'Frontend unit tests failed.' }
    }
    & pnpm build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend type checking or build failed.' }
} finally { Pop-Location }
$mavenArguments = @('-B','-ntp','-Pdistribution','clean','verify')
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
foreach ($directory in @('docs','licenses') | Where-Object { Test-Path -LiteralPath (Join-Path $projectRoot $_) }) { Copy-Item -LiteralPath (Join-Path $projectRoot $directory) -Destination (Join-Path $releaseRoot $directory) -Recurse }
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
foreach ($relative in @('.gitignore','README.md','NOTICE.md',
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
