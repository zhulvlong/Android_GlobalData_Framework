$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ipc = Join-Path $root 'globaldata-sdk\ipc\src\main'
$server = Join-Path $root 'aosp-patch\frameworks\base\services\core\java'
$names = @('IGlobalDataTransport.aidl', 'IDataObserver.aidl', 'IEventListener.aidl')
function Get-AidlContract([string]$path) {
    # 客户端副本含中文教学注释；只比较实际会生成 Binder 事务的声明。
    $source = Get-Content -LiteralPath $path -Raw
    $source = [regex]::Replace($source, '(?s)/\*.*?\*/|//[^\r\n]*', '')
    return [regex]::Replace($source, '\s+', '')
}
foreach ($name in $names) {
    $clientFile = Join-Path $ipc "aidl\com\oem\globaldata\ipc\$name"
    $serverFile = Join-Path $server "com\oem\globaldata\ipc\$name"
    if ((Get-AidlContract $clientFile) -cne (Get-AidlContract $serverFile)) {
        throw "AIDL differs: $name"
    }
}
$clientWire = Join-Path $ipc 'java\com\oem\globaldata\ipc\Wire.java'
$serverWire = Join-Path $server 'com\oem\globaldata\ipc\Wire.java'
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'G:\soft\Java\jdk17' }
$sdkHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'G:\sdk' }
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
$androidJar = Join-Path $sdkHome 'platforms\android-36\android.jar'
$out = Join-Path $root 'verification\build\classes'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$wireClientOut = Join-Path $root 'verification\build\wire-client'
$wireServerOut = Join-Path $root 'verification\build\wire-server'
New-Item -ItemType Directory -Path $wireClientOut, $wireServerOut -Force | Out-Null
& $javac -encoding UTF-8 -d $wireClientOut $clientWire
if ($LASTEXITCODE -ne 0) { throw 'Client Wire compilation failed' }
& $javac -encoding UTF-8 -d $wireServerOut $serverWire
if ($LASTEXITCODE -ne 0) { throw 'Server Wire compilation failed' }
$javap = Join-Path $javaHome 'bin\javap.exe'
$clientConstants = (& $javap -constants -public -classpath $wireClientOut com.oem.globaldata.ipc.Wire) -join "`n"
$serverConstants = (& $javap -constants -public -classpath $wireServerOut com.oem.globaldata.ipc.Wire) -join "`n"
if ($clientConstants -cne $serverConstants) { throw 'Wire protocol constants differ' }
Write-Host 'IPC declaration and constant compatibility: PASS'
$sources = @(
    $clientWire,
    (Join-Path $server 'com\android\server\globaldata\Backend.java'),
    (Join-Path $server 'com\android\server\globaldata\StoreAddress.java'),
    (Join-Path $server 'com\android\server\globaldata\StoredValue.java'),
    (Join-Path $server 'com\android\server\globaldata\TLog.java'),
    (Join-Path $server 'com\android\server\globaldata\MemoryBackend.java'),
    (Join-Path $PSScriptRoot 'stubs\android\util\Log.java'),
    (Join-Path $PSScriptRoot 'stubs\com\android\server\globaldata\AccessController.java'),
    (Join-Path $PSScriptRoot 'BackendSemantics.java')
)
& $javac -encoding UTF-8 -cp $androidJar -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Backend test compilation failed' }
& $java -cp "$out;$androidJar" com.android.server.globaldata.BackendSemantics
if ($LASTEXITCODE -ne 0) { throw 'Backend test failed' }
