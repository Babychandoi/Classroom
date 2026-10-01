# Chạy một script của bộ loadtest BÊN TRONG mạng Docker của stack drill (node:20-alpine, có NET_ADMIN để tạo nhiều IP nguồn).
#   .\loadtest\run-in-drill.ps1 seed.js --users 200
#   .\loadtest\run-in-drill.ps1 scenarios\exam-burst.js --users 200 --duration 20
#   .\loadtest\run-in-drill.ps1 smoke.js
$network = if ($env:LOADTEST_NETWORK) { $env:LOADTEST_NETWORK } else { 'classroom-drill_classroom-net' }
if ($network -notlike '*drill*' -and $env:LOADTEST_ALLOW_ANY_NETWORK -ne '1') {
    Write-Error "Từ chối: mạng '$network' không phải mạng của stack drill (đặt LOADTEST_ALLOW_ANY_NETWORK=1 để bỏ qua)"; exit 2
}
$base = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://frontend' }
$envs = @('-e', "BASE_URL=$base", '-e', 'NODE_OPTIONS=--max-old-space-size=2048')
foreach ($v in 'USERS', 'LOADTEST_STATE', 'LOADTEST_ALLOW_REMOTE', 'LOADTEST_ALLOW_MAIN_STACK') {
    $val = [Environment]::GetEnvironmentVariable($v)
    if ($val) { $envs += @('-e', "$v=$val") }
}
docker run --rm --network $network --cap-add NET_ADMIN @envs -v "${PSScriptRoot}:/work" -w /work node:20-alpine node @args
exit $LASTEXITCODE
