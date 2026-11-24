#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Root = Split-Path -Parent $PSScriptRoot
$Version = "0.5.0"
$ArtifactPrefix = "gen5w-launcher"
$NemesisPkgId = "gen5w.launcher"

# Cross-check version consistency across AndroidManifest.xml, HomeSwitcher.java, Gen5WStudio.csproj, and PresetCatalog.cs
$ManifestRaw = [System.IO.File]::ReadAllText((Join-Path $Root "app\AndroidManifest.xml"), [System.Text.Encoding]::UTF8)
if ($ManifestRaw -notmatch 'android:versionName="([^"]+)"' -or $Matches[1] -ne $Version) {
    throw "Version mismatch: app/AndroidManifest.xml versionName != '$Version'"
}
if ($ManifestRaw -notmatch 'android:versionCode="(\d+)"') {
    throw "Missing android:versionCode in app/AndroidManifest.xml"
}
$ManifestVersionCode = [int]$Matches[1]
$HomeSwitcherRaw = [System.IO.File]::ReadAllText((Join-Path $Root "svc\java\com\gen5w\launcher\svc\HomeSwitcher.java"), [System.Text.Encoding]::UTF8)
if ($HomeSwitcherRaw -notmatch 'EXPECTED_APP_VERSION_CODE\s*=\s*(\d+)\s*;' -or ([int]$Matches[1]) -ne $ManifestVersionCode) {
    throw "VersionCode mismatch: HomeSwitcher.EXPECTED_APP_VERSION_CODE != AndroidManifest.xml versionCode ($ManifestVersionCode)"
}

$CsprojRaw = [System.IO.File]::ReadAllText((Join-Path $Root "studio\Gen5WStudio\Gen5WStudio.csproj"), [System.Text.Encoding]::UTF8)
if ($CsprojRaw -notmatch '<Version>([^<]+)</Version>' -or $Matches[1].Trim() -ne $Version) {
    throw "Version mismatch: studio/Gen5WStudio/Gen5WStudio.csproj <Version> != '$Version'"
}
$PresetCatRaw = [System.IO.File]::ReadAllText((Join-Path $Root "studio\Gen5WStudio\Services\PresetCatalog.cs"), [System.Text.Encoding]::UTF8)
if ($PresetCatRaw -notmatch 'StudioVersion\s*=\s*"([^"]+)"' -or $Matches[1] -ne $Version) {
    throw "Version mismatch: studio/Gen5WStudio/Services/PresetCatalog.cs StudioVersion != '$Version'"
}

# Enforce zero compile-time or runtime HMG-API dependency across all Java sources
$BadHmgRefs = Get-ChildItem -Path @(
    (Join-Path $Root "app\java"),
    (Join-Path $Root "svc\java"),
    (Join-Path $Root "shared\java"),
    (Join-Path $Root "tests\host")
) -Recurse -Filter "*.java" | Select-String -Pattern '\bhmgapi\.(client|engine)\b'
if ($BadHmgRefs) {
    $badList = ($BadHmgRefs | ForEach-Object { "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }) -join "`n  "
    throw "Forbidden HMG-API reference(s) found in Java sources:`n  $badList"
}

function Resolve-ExistingPath([string[]]$Candidates, [string]$Label) {
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path $c)) {
            return (Resolve-Path $c).Path
        }
    }
    throw "Could not locate $Label. Checked:`n  - $($Candidates -join "`n  - ")"
}

$WorkspaceParent = Split-Path -Parent $Root
$SiblingThemerTools = Join-Path $WorkspaceParent "Gen5WThemer\.build-tools"

$JdkBin = Resolve-ExistingPath @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { $null }),
    (Join-Path $Root ".build-tools\jdk\jdk-11.0.21+9\bin"),
    (Join-Path $SiblingThemerTools "jdk\jdk-11.0.21+9\bin")
) "JDK bin directory"

$Jar = Join-Path $JdkBin "jar.exe"
$Javac = Join-Path $JdkBin "javac.exe"
$Java = Join-Path $JdkBin "java.exe"
$Keytool = Join-Path $JdkBin "keytool.exe"

$AndroidJar = Resolve-ExistingPath @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platforms\android-28\android.jar" } else { $null }),
    (Join-Path $Root ".build-tools\android-sdk\platforms\android-28\android.jar"),
    (Join-Path $SiblingThemerTools "android-sdk\platforms\android-28\android.jar")
) "android-28 android.jar"

$Bt = Resolve-ExistingPath @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "build-tools\28.0.3" } else { $null }),
    (Join-Path $Root ".build-tools\android-sdk\build-tools\28.0.3"),
    (Join-Path $SiblingThemerTools "android-sdk\build-tools\28.0.3")
) "Android build-tools 28.0.3"

$Aapt = Join-Path $Bt "aapt.exe"
$D8Jar = Join-Path $Bt "lib\d8.jar"
$ZipAlign = Join-Path $Bt "zipalign.exe"
$ApkSignerJar = Join-Path $Bt "lib\apksigner.jar"

$FrameworkRes = Resolve-ExistingPath @(
    $env:GEN5W_FRAMEWORK_RES,
    (Join-Path $Root "oem\framework-res.apk"),
    (Join-Path $WorkspaceParent "Gen5WThemer\oem-dump\Gen5W_Theme_Dump\04_apks\framework\framework-res.apk")
) "stock Gen5W framework-res.apk (provides android:disableSS)"

$BuildDir = Join-Path $Root "build"
$DistDir = Join-Path $Root "dist"
$KeysDir = Join-Path $Root "keys"

if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Force -Path $BuildDir, $DistDir, $KeysDir | Out-Null

function Invoke-Checked([string]$Exe, [string[]]$ArgList, [string]$StepName) {
    Write-Host "[$StepName]" -ForegroundColor Cyan
    & $Exe @ArgList
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed with exit code $LASTEXITCODE"
    }
}

function Write-Utf8Lf([string]$Path, [string]$Content) {
    $parent = Split-Path -Parent $Path
    if ($parent -and !(Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $normalized = $Content -replace "`r`n", "`n" -replace "`r", "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

function Copy-FileNormalizedLf([string]$Src, [string]$Dst) {
    $text = [System.IO.File]::ReadAllText($Src, [System.Text.Encoding]::UTF8)
    Write-Utf8Lf $Dst $text
}

function Create-ForwardSlashZip([string]$SourceDir, [string]$OutZip) {
    if (Test-Path $OutZip) { Remove-Item -Force $OutZip }
    $srcFull = (Resolve-Path $SourceDir).Path.TrimEnd('\', '/')
    $fs = [System.IO.File]::Open($OutZip, [System.IO.FileMode]::Create)
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = Get-ChildItem -Path $srcFull -Recurse -File | Sort-Object FullName
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($srcFull.Length + 1).Replace('\', '/')
            $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            $inStream = [System.IO.File]::OpenRead($f.FullName)
            try {
                $inStream.CopyTo($entryStream)
            } finally {
                $inStream.Dispose()
                $entryStream.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
        $fs.Dispose()
    }
}

function Assert-KitKatShellCompatible([string[]]$ScriptPaths) {
    Write-Host "[Check shell scripts for KitKat mksh/toolbox compatibility]" -ForegroundColor Cyan
    $forbiddenCmds = @(
        "awk", "sed", "head", "tail", "tr", "cut", "sort", "uniq", "wc", "find", "xargs",
        "md5sum", "sha1sum", "sha256sum", "killall", "pkill", "stat", "dirname", "basename",
        "seq", "expr", "tee", "busybox"
    )
    foreach ($sp in $ScriptPaths) {
        $bytes = [System.IO.File]::ReadAllBytes($sp)
        if ($bytes -contains 13) {
            throw "CRLF (0x0D) byte found in shell script: $sp"
        }
        $raw = [System.Text.Encoding]::UTF8.GetString($bytes)
        $lines = $raw -split "`n"
        if ($lines.Count -eq 0 -or $lines[0] -ne "#!/system/bin/sh") {
            throw "Shell script must start with '#!/system/bin/sh': $sp"
        }
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            if ($line -match '^\s*#') { continue }
            $code = $line -replace '(?<!["''$])\s#.*$', ''
            if ($code -match '\bumount\s+-l\b') {
                throw "Forbidden 'umount -l' (not supported on Gen5W toolbox) in ${sp}:$($i+1)"
            }
            foreach ($cmd in $forbiddenCmds) {
                $escaped = [regex]::Escape($cmd)
                if ($code -match "(?:^|[|;&\s(])$escaped(?:\s|`$|[|;&)])") {
                    throw "Forbidden external command '$cmd' (missing on Gen5W KitKat) in ${sp}:$($i+1): $line"
                }
            }
        }
    }
    Write-Host "ok  Validated $($ScriptPaths.Count) KitKat mksh/toolbox shell script(s)"
}

# 1. Compile shared IPC/telemetry utilities against android.jar
$SharedClasses = Join-Path $BuildDir "shared-classes"
New-Item -ItemType Directory -Force -Path $SharedClasses | Out-Null
$SharedSrc = Get-ChildItem (Join-Path $Root "shared\java") -Recurse -Filter "*.java" |
    Select-Object -ExpandProperty FullName
Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", $AndroidJar,
    "-d", $SharedClasses
) + $SharedSrc) "Compile shared IPC & normalization utilities"

# 2. Ensure dedicated APK signing keystore exists in gitignored keys/
$AppKeystore = Join-Path $KeysDir "gen5w-launcher-app.p12"
if (!(Test-Path $AppKeystore)) {
    Invoke-Checked $Keytool @(
        "-genkeypair",
        "-keystore", $AppKeystore,
        "-storetype", "PKCS12",
        "-storepass", "gen5wlauncher",
        "-keypass", "gen5wlauncher",
        "-alias", "gen5wlauncher",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Gen5W Launcher"
    ) "Generate dedicated APK keystore"
}

# 3. Prepare standalone NEMESIS stage folder
$NemesisStage = Join-Path $BuildDir "nemesis-stage"
$NemesisAppDir = Join-Path $NemesisStage "app"
$NemesisSvcDir = Join-Path $NemesisStage "svc"
$NemesisScriptsDir = Join-Path $NemesisStage "scripts"
New-Item -ItemType Directory -Force -Path $NemesisAppDir, $NemesisSvcDir, $NemesisScriptsDir | Out-Null

# 4. Build HomeSwitcher root service JAR (svc/home-switcher.jar)
$SvcClasses = Join-Path $BuildDir "svc-classes"
New-Item -ItemType Directory -Force -Path $SvcClasses | Out-Null
$SvcSrcFiles = Get-ChildItem (Join-Path $Root "svc\java") -Recurse -Filter "*.java" |
    Select-Object -ExpandProperty FullName
Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", "$AndroidJar;$SharedClasses",
    "-d", $SvcClasses
) + $SvcSrcFiles) "Compile HomeSwitcher standalone service"

$SvcInputJar = Join-Path $BuildDir "svc-input.jar"
Invoke-Checked $Jar @("cf", $SvcInputJar, "-C", $SvcClasses, ".", "-C", $SharedClasses, ".") "Bundle HomeSwitcher classes into jar"
$OutSvcJar = Join-Path $NemesisSvcDir "home-switcher.jar"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $D8Jar,
    "--release", "--min-api", "19",
    "--lib", $AndroidJar,
    "--output", $OutSvcJar,
    $SvcInputJar
) "Dex HomeSwitcher -> svc/home-switcher.jar"

# 5. Build Gen5WLauncher APK (app/Gen5WLauncher.apk)
$AppWork = Join-Path $BuildDir "app"
$AppGen = Join-Path $AppWork "gen"
$AppClasses = Join-Path $AppWork "classes"
$AppDex = Join-Path $AppWork "dex"
New-Item -ItemType Directory -Force -Path $AppGen, $AppClasses, $AppDex | Out-Null

$BaseApk = Join-Path $AppWork "base.apk"
Invoke-Checked $Aapt @(
    "package", "-f", "-m",
    "-J", $AppGen,
    "-S", (Join-Path $Root "app\res"),
    "-A", (Join-Path $Root "app\assets"),
    "-M", (Join-Path $Root "app\AndroidManifest.xml"),
    "-I", $FrameworkRes,
    "-F", $BaseApk
) "Package resources & manifest with aapt"

$AppJavaFiles = @(Get-ChildItem (Join-Path $Root "app\java") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName) +
                @(Get-ChildItem $AppGen -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-cp", "$AndroidJar;$SharedClasses",
    "-d", $AppClasses
) + $AppJavaFiles) "Compile Gen5WLauncher APK Java"

$AppInputJar = Join-Path $AppWork "app-input.jar"
Invoke-Checked $Jar @("cf", $AppInputJar, "-C", $AppClasses, ".", "-C", $SharedClasses, ".") "Bundle Gen5WLauncher classes into jar"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $D8Jar,
    "--release", "--min-api", "19",
    "--lib", $AndroidJar,
    "--output", $AppDex,
    $AppInputJar
) "Dex Gen5WLauncher APK -> classes.dex"

$UnsignedApk = Join-Path $AppWork "unsigned.apk"
$AlignedApk = Join-Path $AppWork "aligned.apk"
Copy-Item $BaseApk $UnsignedApk -Force
$apkZip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $dexFile = Join-Path $AppDex "classes.dex"
    $dexEntry = $apkZip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $dstStream = $dexEntry.Open()
    $srcStream = [System.IO.File]::OpenRead($dexFile)
    try { $srcStream.CopyTo($dstStream) } finally { $srcStream.Dispose(); $dstStream.Dispose() }
} finally {
    $apkZip.Dispose()
}

Invoke-Checked $ZipAlign @("-f", "4", $UnsignedApk, $AlignedApk) "Zipalign APK"

$OutApk = Join-Path $NemesisAppDir "Gen5WLauncher.apk"
Invoke-Checked $Java @(
    "-Xmx1024M", "-jar", $ApkSignerJar,
    "sign",
    "--ks", $AppKeystore,
    "--ks-pass", "pass:gen5wlauncher",
    "--min-sdk-version", "19",
    "--v1-signing-enabled", "true",
    "--v2-signing-enabled", "false",
    "--out", $OutApk,
    $AlignedApk
) "Sign APK (v1 JAR scheme for KitKat)"

# 6. Populate standalone NEMESIS package descriptor & lifecycle scripts
$NemesisTemplateIn = [System.IO.File]::ReadAllText((Join-Path $Root "nemesis\NEMESIS.data.in"), [System.Text.Encoding]::UTF8)
$Descriptor = $NemesisTemplateIn.Replace("@PACKAGE_VERSION@", $Version)
Write-Utf8Lf (Join-Path $NemesisStage "NEMESIS.data") $Descriptor

Copy-FileNormalizedLf (Join-Path $Root "nemesis\svc\home-switcher.sh") (Join-Path $NemesisSvcDir "home-switcher.sh")
foreach ($s in @("preinstall.sh", "postinstall.sh", "preuninstall.sh")) {
    Copy-FileNormalizedLf (Join-Path $Root "nemesis\scripts\$s") (Join-Path $NemesisScriptsDir $s)
}

$AllScripts = Get-ChildItem $NemesisStage -Recurse -Filter "*.sh" | Select-Object -ExpandProperty FullName
Assert-KitKatShellCompatible $AllScripts

$NemesisPkgZip = Join-Path $DistDir "$ArtifactPrefix-$Version-nemesis-package.zip"
Create-ForwardSlashZip $NemesisStage $NemesisPkgZip

# 7. Build NEMESIS USB installer wrapper ZIP + copy into Gen5W Studio embedded Assets
$NemesisStickStage = Join-Path $BuildDir "nemesis-stick"
$NemesisStickPkgs = Join-Path $NemesisStickStage "packages"
New-Item -ItemType Directory -Force -Path $NemesisStickPkgs | Out-Null
Copy-Item $NemesisPkgZip (Join-Path $NemesisStickPkgs "$NemesisPkgId.zip") -Force
Write-Utf8Lf (Join-Path $NemesisStickStage "NEMESIS.data") "[nemesis]`nversion=1.0`ntype=install`npath=packages/$NemesisPkgId.zip`n"

$ReadmeIn = [System.IO.File]::ReadAllText((Join-Path $Root "nemesis\README.txt"), [System.Text.Encoding]::UTF8)
$ReadmeOut = $ReadmeIn.
    Replace("@NAME@", "Gen5W Theme Launcher").
    Replace("@VERSION@", $Version).
    Replace("@PACKAGE_ID@", $NemesisPkgId)
Write-Utf8Lf (Join-Path $NemesisStickStage "README.txt") $ReadmeOut

$NemesisZip = Join-Path $DistDir "$ArtifactPrefix-$Version-nemesis.zip"
Create-ForwardSlashZip $NemesisStickStage $NemesisZip

$StudioInstallerAsset = Join-Path $Root "studio\Gen5WStudio\Assets\gen5w-launcher-nemesis.zip"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StudioInstallerAsset) | Out-Null
Copy-Item $NemesisZip $StudioInstallerAsset -Force

# 8. Local Verification Assertions
Write-Host "[Verify Artifacts]" -ForegroundColor Cyan

$XmlDump = & $Aapt dump xmltree $OutApk AndroidManifest.xml | Out-String
if ($LASTEXITCODE -ne 0) { throw "aapt dump xmltree failed" }
if ($XmlDump -notmatch "0x0101040f.*0xffffffff") {
    throw "Verification failed: android:disableSS(0x0101040f)=true missing from compiled AndroidManifest.xml"
}
if ($XmlDump -notmatch "android\.intent\.category\.HOME") {
    throw "Verification failed: android.intent.category.HOME missing from AndroidManifest.xml"
}
if ($XmlDump -notmatch "ThemeManagerActivity") {
    throw "Verification failed: ThemeManagerActivity missing from AndroidManifest.xml"
}
if ($XmlDump -notmatch "android\.permission\.INTERNET") {
    throw "Verification failed: android.permission.INTERNET missing from AndroidManifest.xml"
}
if ($XmlDump -notmatch "android\.intent\.category\.LAUNCHER") {
    throw "Verification failed: android.intent.category.LAUNCHER missing (required for NEMESIS launcher grid icon)"
}
Write-Host "ok  APK manifest: disableSS=true (0x0101040f), CATEGORY_HOME, CATEGORY_LAUNCHER, INTERNET, and ThemeManagerActivity present"

Invoke-Checked $Java @("-Xmx512M", "-jar", $ApkSignerJar, "verify", "--verbose", "--min-sdk-version", "19", $OutApk) "Verify APK v1 signature"

$svcArchive = [System.IO.Compression.ZipFile]::OpenRead($OutSvcJar)
try {
    if ($null -eq $svcArchive.GetEntry("classes.dex")) { throw "svc/home-switcher.jar missing classes.dex" }
    Write-Host "ok  home-switcher.jar: classes.dex present"
} finally {
    $svcArchive.Dispose()
}

$InnerMeta = [System.IO.File]::ReadAllText((Join-Path $NemesisStage "NEMESIS.data"), [System.Text.Encoding]::UTF8)
foreach ($reqLine in @(
    'id=gen5w.launcher',
    'type=application,service',
    "package-version=$Version",
    'name=Gen5W Theme Launcher',
    'short-desc="Themed Homescreen Replacement"',
    'detailed-desc="Replaces the main homescreen. Download and make custom themes from https://market.ioniqthemes.com"'
)) {
    if (-not $InnerMeta.Contains($reqLine)) {
        throw "Inner NEMESIS.data missing required line: $reqLine"
    }
}
if ($InnerMeta -match '(?m)^depends=' -or $InnerMeta -match 'hmgapi') {
    throw "Inner NEMESIS.data must NOT depend on or reference hmgapi"
}
Write-Host "ok  Inner NEMESIS.data standalone metadata verified (id=$NemesisPkgId, no depends)"

foreach ($lfFile in (@(Get-ChildItem $NemesisStage -Recurse -Include "*.sh","NEMESIS.data") + @(Get-ChildItem $NemesisStickStage -Recurse -Include "NEMESIS.data","README.txt"))) {
    $bytes = [System.IO.File]::ReadAllBytes($lfFile.FullName)
    if ($bytes -contains 13) { throw "CRLF found in $($lfFile.FullName)" }
}
Write-Host "ok  All scripts and descriptors strictly LF (0 CR bytes)"

foreach ($z in @($NemesisPkgZip, $NemesisZip)) {
    $za = [System.IO.Compression.ZipFile]::OpenRead($z)
    try {
        foreach ($e in $za.Entries) {
            if ($e.FullName.Contains('\')) { throw "Backslash ZIP entry '$($e.FullName)' in $z" }
        }
        Write-Host "ok  ZIP forward-slash entries clean: $(Split-Path -Leaf $z) ($($za.Entries.Count) entries)"
    } finally {
        $za.Dispose()
    }
}
$usbZa = [System.IO.Compression.ZipFile]::OpenRead($NemesisZip)
try {
    $hasStickMeta = $null -ne $usbZa.GetEntry("NEMESIS.data")
    $hasInnerPkg = $null -ne $usbZa.GetEntry("packages/$NemesisPkgId.zip")
    if (-not ($hasStickMeta -and $hasInnerPkg)) { throw "NEMESIS USB stick ZIP missing type=install NEMESIS.data or packages/$NemesisPkgId.zip" }
    Write-Host "ok  NEMESIS USB stick ZIP verified: type=install -> packages/$NemesisPkgId.zip"
} finally {
    $usbZa.Dispose()
}

# 9. Validate default ThemePack JSON & SVG icon assets
Get-Content (Join-Path $Root "app\assets\packs\default\layout.json") -Raw | ConvertFrom-Json | Out-Null
$SvgFiles = Get-ChildItem (Join-Path $Root "app\assets\packs\default\icons") -Filter "*.svg"
foreach ($s in $SvgFiles) {
    [xml]$doc = Get-Content $s.FullName -Raw
    if ($doc.DocumentElement.LocalName -ne "svg") { throw "Invalid SVG document element in $($s.Name)" }
}
Write-Host "ok  Validated default ThemePack layout.json and $($SvgFiles.Count) SVG icon assets"

# 10. Run host JVM unit test suite for Theme Store TLS, JSON parsing & ZIP security (zero external dependencies)
$HostTestDir = Join-Path $BuildDir "host-test-classes"
New-Item -ItemType Directory -Force -Path $HostTestDir | Out-Null
$HostTestSrcs = @(
    (Join-Path $Root "shared\java\com\gen5w\launcher\ipc\TemperatureNormalizer.java"),
    (Join-Path $Root "app\java\com\gen5w\launcher\store\StoreTlsHelper.java"),
    (Join-Path $Root "app\java\com\gen5w\launcher\store\StoreThemeListing.java"),
    (Join-Path $Root "app\java\com\gen5w\launcher\store\ThemeStoreClient.java"),
    (Join-Path $Root "app\java\com\gen5w\launcher\render\SafeThemeZipExtractor.java")
) + @(Get-ChildItem (Join-Path $Root "tests\host") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName) `
  + @(Get-ChildItem (Join-Path $Root "app\java\com\gen5w\launcher\model") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)

Invoke-Checked $Javac (@(
    "--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn",
    "-d", $HostTestDir
) + $HostTestSrcs) "Compile host Store & ZIP security tests"

Invoke-Checked $Java @("-cp", $HostTestDir, "ThemeStoreHostTest") "Run host Store & ZIP security tests"

Write-Host "`nBUILD SUCCEEDED" -ForegroundColor Green
Write-Host "  NEMESIS Package ZIP : $NemesisPkgZip"
Write-Host "  NEMESIS USB ZIP     : $NemesisZip"
