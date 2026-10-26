# Fails the build if libmpv-2.dll (or any bundled DLL) imports a DLL that is neither shipped
# next to Nuvio.exe nor provided by Windows. This is exactly what produces the runtime error
# "Unable to load libmpv-2.dll" on a machine that never had MSYS2 installed.
#
# Usage: verify-windows-runtime.ps1 -AppDir <folder containing player_bridge.dll> [-Objdump <path>]
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [string]$Objdump = 'C:\msys64\ucrt64\bin\objdump.exe'
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Objdump)) { throw "objdump not found at $Objdump (install mingw-w64-ucrt-x86_64-binutils)" }
foreach ($required in 'player_bridge.dll', 'libmpv-2.dll') {
    if (-not (Test-Path (Join-Path $AppDir $required))) { throw "$required missing from $AppDir" }
}

# Files that ship in the app dir, case-insensitive.
$shipped = @{}
Get-ChildItem $AppDir -Filter *.dll -File | ForEach-Object { $shipped[$_.Name.ToLowerInvariant()] = $_.FullName }

$system32 = Join-Path $env:WINDIR 'System32'
function Test-SystemDll([string]$name) {
    $n = $name.ToLowerInvariant()
    if ($n -like 'api-ms-win-*' -or $n -like 'ext-ms-win-*') { return $true }
    return (Test-Path (Join-Path $system32 $name))
}

$missing = @{}   # missing dll -> list of importers
$seen = @{}
$queue = [System.Collections.Generic.Queue[string]]::new()
foreach ($root in 'player_bridge.dll', 'libmpv-2.dll') { $queue.Enqueue($root.ToLowerInvariant()) }

while ($queue.Count -gt 0) {
    $name = $queue.Dequeue()
    if ($seen.ContainsKey($name)) { continue }
    $seen[$name] = $true
    $path = $shipped[$name]
    $imports = & $Objdump -p $path 2>$null |
        Select-String -Pattern '^\s*DLL Name:\s*(.+)$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value.Trim() }
    foreach ($dep in $imports) {
        $key = $dep.ToLowerInvariant()
        if ($shipped.ContainsKey($key)) { $queue.Enqueue($key); continue }
        if (Test-SystemDll $dep) { continue }
        if (-not $missing.ContainsKey($dep)) { $missing[$dep] = @() }
        $missing[$dep] += $name
    }
}

Write-Host "Checked $($seen.Count) DLLs reachable from libmpv-2.dll / player_bridge.dll."
if ($missing.Count -gt 0) {
    $missing.GetEnumerator() | Sort-Object Name | ForEach-Object {
        Write-Host "MISSING: $($_.Key)  (imported by: $(($_.Value | Select-Object -Unique) -join ', '))"
    }
    throw "$($missing.Count) DLL(s) required by the player runtime are not bundled in $AppDir"
}
Write-Host 'Player runtime import closure is complete.'
