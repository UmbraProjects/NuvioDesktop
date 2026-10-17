# nativeSmoke (native-image plan step 5): launch the native build repeatedly and fail on anything a
# signed-out launch can show. Run by `gradlew :composeApp:nativeSmoke`; usable on its own too.
#
# Each launch runs against a throwaway data directory (NUVIO_NATIVE_SELFTEST_DATA_DIR), so the
# user's profile, log and single-instance lock are never touched; the first launch is a fresh
# install, the rest are warm starts. NUVIO_NATIVE_SELFTEST makes the app run its probes
# (NativeImageSupport.startSelfTestIfRequested) and print "(NativeSelfTest) done".
#
# Many launches, not one: races hide in a single launch (the skiko FontMgr race failed 1 launch
# in 3-7 before its fix).
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [int]$Runs = 15,
    [int]$TimeoutSeconds = 90,
    [string]$WorkDir = (Join-Path $env:TEMP 'nuvio-native-smoke'),
    # mpv's built-in test pattern: proves the player bridge, libmpv and the event sink load
    # without network or a media file.
    [string]$PlayerUrl = 'av://lavfi:testsrc=size=640x360:rate=24',
    [switch]$SkipPlayer,
    # Capture stdout/stderr per launch. Off by default: users start the exe without them, and the
    # native build has crashed only in that situation.
    [switch]$Redirect
)
$ErrorActionPreference = 'Stop'

Add-Type @"
using System; using System.Runtime.InteropServices;
public static class NuvioSmokeWin32 {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetWindowDpiAwarenessContext(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetAwarenessFromDpiAwarenessContext(IntPtr c);
}
"@

# Known, harmless reports from a QA build (-Pnuvio.native.qa=true), which prints every probe it
# would otherwise answer silently. Plan step 3 rounds 3-4 explain each.
$harmless = @(
    'Note: this run will print partial stack traces',  # the QA build's one-time header for those reports
    "class 'kotlin.String'",                # kotlin-reflect probing; caught, falls back
    'access class Error\.',                 # quickjs-kt maps JS error names to Java classes by design
    'access class TypeError\.',
    'access class InternalError\.',
    'access class RangeError\.',
    'access class SyntaxError\.',
    'access class ReferenceError\.',
    'Module#getDescriptor',                 # coroutine debug metadata, only when a continuation is printed
    '#label'''
)
$failurePatterns = 'MissingReflectionRegistration|MissingJNIRegistration|MissingResourceRegistration|' +
    'not registered|UnsatisfiedLinkError|NoClassDefFound|Could not initialize class|' +
    'InaccessibleObjectException|NoSuchMethodError|NoSuchFieldError|\(NativeSelfTest\) .* FAILED'

if (-not (Test-Path $Exe)) { Write-Output "FAIL: $Exe does not exist"; exit 1 }
$exePath = (Resolve-Path $Exe).Path
$running = Get-Process Nuvio -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exePath }
if ($running) { Write-Output "FAIL: $exePath is running; close it first"; exit 1 }

# Absolute: the app starts in the exe's folder and would resolve a relative path from there, while
# this script watched the path from its own folder (first run: 90 s timeouts on every launch).
if (-not [IO.Path]::IsPathRooted($WorkDir)) { $WorkDir = Join-Path (Get-Location).Path $WorkDir }
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
$dataDir = Join-Path $WorkDir 'data'
$outDir = Join-Path $WorkDir 'runs'
if (Test-Path $WorkDir) { Remove-Item $WorkDir -Recurse -Force }
New-Item -ItemType Directory -Force $dataDir, $outDir | Out-Null
$log = Join-Path $dataDir 'logs\nuvio.log'

function Read-NewLog([long]$from) {
    if (-not (Test-Path $log)) { return @() }
    $fs = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try { $fs.Seek([Math]::Min($from, $fs.Length), 'Begin') | Out-Null; (New-Object IO.StreamReader($fs)).ReadToEnd() -split "`r?`n" }
    finally { $fs.Close() }
}

function Get-WindowAwareness([int]$processId) {
    $found = New-Object System.Collections.ArrayList
    [NuvioSmokeWin32]::EnumWindows({ param($h, $l) $id = 0; [NuvioSmokeWin32]::GetWindowThreadProcessId($h, [ref]$id) | Out-Null
        if ($id -eq $processId -and [NuvioSmokeWin32]::IsWindowVisible($h)) { [void]$found.Add($h) }; $true }, [IntPtr]::Zero) | Out-Null
    # 0 unaware (DPI-virtualised: the embedded manifest is missing), 1 system, 2 per-monitor.
    $found | ForEach-Object { [NuvioSmokeWin32]::GetAwarenessFromDpiAwarenessContext([NuvioSmokeWin32]::GetWindowDpiAwarenessContext($_)) }
}

function Invoke-Launch([int]$run, [string[]]$markers, [hashtable]$extraEnv) {
    $from = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
    $env:NUVIO_NATIVE_SELFTEST = '1'
    $env:NUVIO_NATIVE_SELFTEST_DATA_DIR = $dataDir
    foreach ($key in $extraEnv.Keys) { Set-Item "env:$key" $extraEnv[$key] }
    $stderr = Join-Path $outDir "run-$run.err.txt"
    # Started like a user starts it (Explorer, shortcut): no stdout/stderr. With redirected output
    # the native build survived a crash that a double-click hit every time (2026-10-17), so only
    # -Redirect (for diagnosing a failure) captures them.
    $p = if ($Redirect) {
        Start-Process $exePath -WorkingDirectory (Split-Path $exePath) -PassThru -RedirectStandardOutput (Join-Path $outDir "run-$run.out.txt") -RedirectStandardError $stderr
    } else {
        Start-Process $exePath -WorkingDirectory (Split-Path $exePath) -PassThru
    }
    foreach ($key in $extraEnv.Keys) { Remove-Item "env:$key" }
    $t0 = Get-Date
    $problems = New-Object System.Collections.ArrayList
    $seen = @()
    while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Milliseconds 500
        if ($p.HasExited) { break }
        # Safety stop: a live app that has not started the throwaway log within 20 s is logging
        # (and storing) somewhere else, possibly a real profile. End the whole smoke run here.
        if (((Get-Date) - $t0).TotalSeconds -gt 20 -and -not (Test-Path $log)) {
            Stop-Process -Id $p.Id -Force
            Write-Output "ABORT: launch $run never wrote $log; stopping before it can touch a real profile"
            exit 2
        }
        $text = (Read-NewLog $from) -join "`n"
        $seen = @($markers | Where-Object { $text -match [regex]::Escape($_) })
        if ($seen.Count -eq $markers.Count) { break }
    }
    $seconds = [Math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $awareness = @()
    $wasAlive = -not $p.HasExited
    if ($wasAlive) {
        $awareness = @(Get-WindowAwareness $p.Id)
        Stop-Process -Id $p.Id -Force
        $p.WaitForExit(10000) | Out-Null
    } else {
        [void]$problems.Add("process exited early (code $($p.ExitCode))")
    }
    $missing = @($markers | Where-Object { $_ -notin $seen })
    if ($missing) { [void]$problems.Add("missing marker(s): $($missing -join ', ')") }
    if ($wasAlive -and $awareness.Count -eq 0) { [void]$problems.Add('no visible window found to check DPI awareness') }
    elseif ($awareness | Where-Object { $_ -ne 2 }) { [void]$problems.Add("window not per-monitor DPI aware (awareness $($awareness -join ','))") }
    $lines = @(Read-NewLog $from) + @(Get-Content $stderr -ErrorAction SilentlyContinue)
    # Case-sensitive: "(NativeSelfTest) done passed=7 failed=0" must not match "FAILED".
    $bad = @($lines | Where-Object { $_ -cmatch $failurePatterns } | Where-Object { $line = $_; -not ($harmless | Where-Object { $line -match $_ }) })
    $bad | Select-Object -Unique -First 8 | ForEach-Object { [void]$problems.Add("log: $($_.Substring(0, [Math]::Min(220, $_.Length)))") }
    Copy-Item $log (Join-Path $outDir "run-$run.nuvio.log") -ErrorAction SilentlyContinue
    [pscustomobject]@{ Run = $run; Seconds = $seconds; Problems = $problems; Awareness = ($awareness -join ',') }
}

$failures = 0
$appMarkers = @('(LaunchOverlay) up', '(NativeSelfTest) done')
for ($run = 1; $run -le $Runs; $run++) {
    $result = Invoke-Launch $run $appMarkers @{}
    $kind = if ($run -eq 1) { 'fresh' } else { 'warm' }
    if ($result.Problems.Count -eq 0) { Write-Output ("run {0,2} ({1}) ok in {2}s, DPI awareness {3}" -f $run, $kind, $result.Seconds, $result.Awareness) }
    else { $failures++; Write-Output ("run {0,2} ({1}) FAIL after {2}s" -f $run, $kind, $result.Seconds); $result.Problems | ForEach-Object { Write-Output "    $_" } }
}
if (-not $SkipPlayer) {
    $result = Invoke-Launch ($Runs + 1) @('(PlayerSmoke) controller ready') @{ NUVIO_DESKTOP_SMOKE_PLAYER_URL = $PlayerUrl }
    if ($result.Problems.Count -eq 0) { Write-Output "player ok in $($result.Seconds)s" }
    else { $failures++; Write-Output "player FAIL after $($result.Seconds)s"; $result.Problems | ForEach-Object { Write-Output "    $_" } }
}
Write-Output "logs: $outDir"
if ($failures -gt 0) { Write-Output "nativeSmoke FAILED: $failures launch(es)"; exit 1 }
Write-Output "nativeSmoke passed"
exit 0
