param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [ValidateSet('preflight','run')][string]$Mode='preflight',
    [string]$ExternalWritePlan='',
    [ValidateRange(1,1000)][int]$CheckAttempt=1,
    [ValidateSet('unit','lint','all')][string]$Scope='all'
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
Set-Location -LiteralPath 'E:\daiyu'
$Root='E:\daiyu'
function Assert-RootPath([string]$Path) {
    $p=[IO.Path]::GetFullPath($Path)
    if($p -ne $Root -and -not $p.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'path_outside_workspace'}
    $current=$p
    while($current -and $current -ne 'E:\') {
        if(Test-Path -LiteralPath $current) {
            if((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'unexpected_reparse_point'}
        }
        $current=Split-Path -Parent $current
    }
    return $p
}
$Snapshot=Assert-RootPath $Snapshot
$Source=Assert-RootPath (Join-Path $Snapshot 'source')
$ReportName='cooperation-'+$CheckAttempt+'-'+$Scope
$Report=Assert-RootPath (Join-Path $Snapshot $ReportName)
$sourceLock=[IO.File]::Open((Join-Path $Snapshot 'source-operation.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$Manifest=Get-Content -LiteralPath (Join-Path $Snapshot 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$Sha=$Manifest.source_sha
$Config=$Manifest.config_id
if($Sha -notmatch '^[a-f0-9]{40}$'){throw 'invalid_source_sha'}
foreach($f in $Manifest.files) {
    if([IO.Path]::IsPathRooted($f.path) -or $f.path -match '(^|[\\/])\.\.([\\/]|$)' -or $f.path.Contains(':')){throw 'unsafe_manifest_path'}
    $full=Assert-RootPath (Join-Path $Source $f.path)
    if((Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.sha256){throw 'snapshot_hash_mismatch'}
}
if($Mode -eq 'run' -and (Test-Path -LiteralPath (Join-Path $Report 'status.json'))){throw 'attempt_already_started_do_not_repeat'}
New-Item -ItemType Directory -Path $Report -Force | Out-Null
if($Mode -eq 'run') {
    $lock=[IO.File]::Open((Join-Path $Report 'attempt.lock'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    $lock.Dispose()
}
function Write-JsonAtomic($Value,[string]$Path) {
    $tmp=$Path+'.tmp'
    [IO.File]::WriteAllText($tmp,($Value | ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path -LiteralPath $Path){[IO.File]::Replace($tmp,$Path,($Path+'.bak'),$true); Remove-Item -LiteralPath ($Path+'.bak')}else{[IO.File]::Move($tmp,$Path)}
}
$env:TEMP=Assert-RootPath "$Root\temp"; $env:TMP=$env:TEMP
$env:HOME=Assert-RootPath "$Root\cache\home"; $env:USERPROFILE=$env:HOME
$env:APPDATA=Assert-RootPath "$Root\cache\appdata"; $env:LOCALAPPDATA=Assert-RootPath "$Root\cache\localappdata"
$env:JAVA_HOME=Assert-RootPath "$Root\environment\jdk-27+35"
$env:GRADLE_USER_HOME=Assert-RootPath "$Root\cache\gradle"
$env:ANDROID_HOME=Assert-RootPath "$Root\environment\android-sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME=Assert-RootPath "$Root\cache\android"; $env:ANDROID_AVD_HOME=Assert-RootPath "$Root\cache\android\avd"
$env:PSModuleAnalysisCachePath=Assert-RootPath "$Root\cache\powershell-cache.bin"
foreach($n in @('JAVA_OPTS','GRADLE_OPTS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')){Remove-Item -LiteralPath "Env:$n" -ErrorAction SilentlyContinue}
$env:JAVA_TOOL_OPTIONS="-XX:-UsePerfData -Duser.home=$env:HOME -Djava.io.tmpdir=$env:TEMP -XX:ErrorFile=$Root\checks\java-error-%p.log"
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
foreach($p in @($env:TEMP,$env:HOME,$env:APPDATA,$env:LOCALAPPDATA,$env:GRADLE_USER_HOME,$env:ANDROID_USER_HOME)){New-Item -ItemType Directory -Path $p -Force | Out-Null}
if(-not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")){throw 'jdk_missing'}
$Workers=[Environment]::ProcessorCount
$Guard=Join-Path $Report 'checks-only.init.gradle'
Copy-Item -LiteralPath (Join-Path $Source 'scripts\cooperation\checks_only.init.gradle') -Destination $Guard
$ExternalEntries=@()
if($ExternalWritePlan) {
    $planPath=Assert-RootPath $ExternalWritePlan
    $ExternalEntries=@(Get-Content -LiteralPath $planPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    $Allowed=@('TEMP','TMP','HOME','USERPROFILE','APPDATA','LOCALAPPDATA','GRADLE_USER_HOME','ANDROID_USER_HOME','ANDROID_AVD_HOME')
    foreach($entry in $ExternalEntries) {
        if($entry.environment_variable -notin $Allowed -or -not $entry.reason -or -not $entry.tool -or $entry.operation -ne 'cache_or_temp'){throw 'external_plan_invalid'}
        if(-not [IO.Path]::IsPathRooted($entry.path)){throw 'external_path_not_absolute'}
        $path=[IO.Path]::GetFullPath($entry.path)
        if($path -eq [IO.Path]::GetPathRoot($path)){throw 'external_drive_root_refused'}
        # A plan is only for normal tool cache/temp files, not protected OS/config changes.
        if($path -match '(?i)^[a-z]:\\(Windows|Program Files(?: \(x86\))?|ProgramData)(\\|$)'){throw 'protected_path_requires_separate_authorization'}
        [Environment]::SetEnvironmentVariable($entry.environment_variable,$path,'Process')
        $entry.path=$path
    }
    $env:JAVA_TOOL_OPTIONS="-XX:-UsePerfData -Duser.home=$env:HOME -Djava.io.tmpdir=$env:TEMP -XX:ErrorFile=$Root\checks\java-error-%p.log"
}
$Policy=[ordered]@{
    workspace=$Root;system_sandbox=$false;policy='reviewed_checks_necessary_external_writes_allowed_and_reported'
    external_writes_planned=$ExternalEntries;external_writes_observed=@();external_plan_result=if($ExternalEntries.Count){'configured; actual writes require tool evidence'}else{'no_external_writes_requested'}
    observation_scope='Explicit script destinations, process cache/home/temp configuration and captured tool diagnostics; not an OS-wide filesystem trace.'
    daemon='enabled; registry and logs under GRADLE_USER_HOME';workers=$Workers
}
$Policy | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Report 'write-policy.json') -Encoding UTF8
$EnvRecord=[ordered]@{java_home=$env:JAVA_HOME;gradle_user_home=$env:GRADLE_USER_HOME;android_home=$env:ANDROID_HOME;temp=$env:TEMP;home=$env:HOME;workers=$Workers;guard_sha256=(Get-FileHash $Guard -Algorithm SHA256).Hash.ToLowerInvariant();parallel=$true;build_cache=$true;daemon=$true;java_tool_options=$env:JAVA_TOOL_OPTIONS}
$EnvRecord | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Report 'environment.json') -Encoding UTF8
Set-Location -LiteralPath $Source
$Common=@('--daemon','--parallel','--build-cache',"--max-workers=$Workers",'--console=plain','--warning-mode=fail','-I',$Guard)
if($Mode -eq 'preflight') {
    $ErrorActionPreference='Continue'
    $PreviewTasks=if($Scope -eq 'unit'){@(':core-domain:test')}elseif($Scope -eq 'lint'){@(':app:lintDebug')}else{@(':core-domain:test',':app:lintDebug')}
    & .\gradlew.bat @PreviewTasks '--dry-run' @Common *> (Join-Path $Report 'preflight.log')
    $code=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    [ordered]@{source_sha=$Sha;exit_code=$code;mode='dry_run';apk_built=$false;workers=$Workers} | ConvertTo-Json
    exit $code
}
$CheckId='pc-'+$Sha.Substring(0,12)
$Status=[ordered]@{check_id=$CheckId;check_attempt=$CheckAttempt;source_sha=$Sha;config_id=$Config;result='running';pid=$PID;started_at=[DateTime]::UtcNow.ToString('o');items=@()}
function Save-Status { Write-JsonAtomic $Status (Join-Path $Report 'status.json') }
Save-Status
$Final=0
$PackageScan='not_run'
try {
    $Specs=@(@{name='unit';task=':core-domain:test';reports='core-domain\build\test-results\test'},@{name='lint';task=':app:lintDebug';reports='app\build\reports'})
    foreach($spec in ($Specs | Where-Object {$Scope -eq 'all' -or $_.name -eq $Scope})) {
        $item=[ordered]@{check_id=$CheckId;check_attempt=$CheckAttempt;config_id=$Config;scope=$spec.name;name=$spec.name;task=$spec.task;command=@('gradlew.bat',$spec.task)+$Common;source_sha=$Sha;incremental_base_sha=$Sha;diff='none; exact immutable snapshot; Gradle build cache enabled';result='running';exit_code=$null;started_at=[DateTime]::UtcNow.ToString('o')}
        $Status.items+=,$item; Save-Status
        $log=Join-Path $Report ($spec.name+'.log')
        $ErrorActionPreference='Continue'
        & .\gradlew.bat $spec.task @Common *> $log
        $code=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        $item.exit_code=$code; $item.result=if($code -eq 0){'passed'}else{'failed'};$item.ended_at=[DateTime]::UtcNow.ToString('o')
        $item.log_sha256=(Get-FileHash $log -Algorithm SHA256).Hash.ToLowerInvariant()
        $dest=Join-Path $Report $spec.name;New-Item -ItemType Directory -Path $dest -Force | Out-Null
        if(Test-Path -LiteralPath $spec.reports){Copy-Item -LiteralPath $spec.reports -Destination (Join-Path $dest 'reports') -Recurse -Force}
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive=Join-Path $Report ($spec.name+'-reports.zip')
        [IO.Compression.ZipFile]::CreateFromDirectory($dest,$archive)
        $item.report_sha256=(Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        $item.environment=$EnvRecord
        Write-JsonAtomic $item (Join-Path $Report ($spec.name+'-record.json'))
        Save-Status
        if($code -ne 0){$Final=$code;break}
    }
    # Recheck the tracked input bytes after executing repository code.
    foreach($f in $Manifest.files){if((Get-FileHash -LiteralPath (Join-Path $Source $f.path) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.sha256){throw 'source_changed_during_checks'}}
    $packages=@(Get-ChildItem -LiteralPath (Join-Path $Source 'app'),(Join-Path $Source 'core-domain') -Recurse -File | Where-Object {$_.Extension -in @('.apk','.aab','.apks')})
    $PackageScan=if($packages.Count){'failed'}else{'passed'}
    if($packages.Count){throw 'unexpected_install_package'}
    $Status.result=if($Final -eq 0){'passed'}else{'failed'}
} catch {
    $Status.result='failed';$Status.error_type=$_.Exception.GetType().Name;$Status.error=$_.Exception.Message;$Final=1
} finally {
    $Status.ended_at=[DateTime]::UtcNow.ToString('o');$Status.exit_code=$Final;$Status.package_scan=$PackageScan;$Status.apk_built=if($PackageScan -eq 'passed'){$false}else{$null}
    Save-Status
}
exit $Final
