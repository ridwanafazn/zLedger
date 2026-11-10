<#
.SYNOPSIS
    Uji konkurensi redeem voucher (anti-overselling) via HTTP.

.DESCRIPTION
    Membuat N akun A_POIN, satu voucher berkuota K, lalu mengirim N request
    redeem secara PARALEL. Ekspektasi: tepat K yang sukses, sisanya ditolak,
    sisa kuota 0, tidak ada 5xx.

.EXAMPLE
    powershell -File loadtest/voucher-loadtest.ps1 -Users 20 -Quota 5
#>
param(
    [int]$Users = 20,
    [int]$Quota = 5,
    [int]$PointsCost = 100,
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$tmp = Join-Path $env:TEMP "finledger-voucher"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Login([string]$user, [string]$pass) {
    $file = Join-Path $tmp "login.json"
    [IO.File]::WriteAllText($file, "{`"username`":`"$user`",`"password`":`"$pass`"}")
    $raw = curl.exe -s -X POST "$BaseUrl/api/v1/auth/login" -H "Content-Type: application/json" --data-binary "@$file"
    return ($raw | ConvertFrom-Json).accessToken
}

$admin = Login "admin" "admin123"
$customer = Login "customer" "customer123"

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 6).ToUpper()
$owners = @()
for ($i = 0; $i -lt $Users; $i++) {
    $owner = "vl-$suffix-$i"
    $owners += $owner
    $file = Join-Path $tmp "acc.json"
    [IO.File]::WriteAllText($file, (@{ ownerId = $owner; accountName = "voucher wallet $i"; accountType = "LIABILITY"; currency = "A_POIN"; initialBalance = 1000 } | ConvertTo-Json -Compress))
    $code = curl.exe -s -o NUL -w "%{http_code}" -X POST "$BaseUrl/api/v1/ledger/accounts" `
        -H "Authorization: Bearer $admin" -H "Content-Type: application/json" `
        -H "X-Idempotency-Key: vl-acc-$owner" --data-binary "@$file"
    if ($code -ne "201") { throw "Gagal membuat akun $owner ($code)" }
}

$now = Get-Date
$file = Join-Path $tmp "voucher.json"
$body = @{
    code = "LOAD$suffix"; name = "Load Test Voucher"; pointsCost = $PointsCost
    totalQuota = $Quota
    startsAt = $now.AddMinutes(-1).ToString("yyyy-MM-ddTHH:mm:ss")
    endsAt = $now.AddHours(1).ToString("yyyy-MM-ddTHH:mm:ss")
} | ConvertTo-Json -Compress
[IO.File]::WriteAllText($file, $body)
$voucher = curl.exe -s -X POST "$BaseUrl/api/v1/vouchers" -H "Authorization: Bearer $admin" -H "Content-Type: application/json" --data-binary "@$file" | ConvertFrom-Json
$voucherId = $voucher.id
if (-not $voucherId) { throw "Gagal membuat voucher" }

[IO.File]::WriteAllText($file, '{"status":"ACTIVE"}')
$code = curl.exe -s -o NUL -w "%{http_code}" -X PATCH "$BaseUrl/api/v1/vouchers/$voucherId/status" `
    -H "Authorization: Bearer $admin" -H "Content-Type: application/json" --data-binary "@$file"
if ($code -ne "200") { throw "Gagal mengaktifkan voucher ($code)" }

Write-Host "=== Voucher quota race: $Users request paralel, kuota $Quota ===" -ForegroundColor Cyan
[System.Net.ServicePointManager]::DefaultConnectionLimit = 200

$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$client.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $admin)

$sw = [Diagnostics.Stopwatch]::StartNew()
$tasks = @()
for ($i = 0; $i -lt $Users; $i++) {
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/v1/vouchers/$voucherId/redeem")
    $req.Headers.Add("X-Idempotency-Key", "vl-redeem-$suffix-$i")
    $json = @{ ownerId = $owners[$i] } | ConvertTo-Json -Compress
    $req.Content = New-Object System.Net.Http.StringContent(
        $json, [System.Text.Encoding]::UTF8, "application/json")
    $tasks += $client.SendAsync($req)
}
[System.Threading.Tasks.Task]::WaitAll($tasks)
$sw.Stop()

$codes = @{}
$times = @()
foreach ($task in $tasks) {
    $response = $task.Result
    $code = [int]$response.StatusCode
    if ($codes.ContainsKey($code)) { $codes[$code]++ } else { $codes[$code] = 1 }
}
$client.Dispose()

"Kode HTTP   : " + (($codes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ", ")
"Durasi total: {0:N2} s" -f $sw.Elapsed.TotalSeconds

$after = curl.exe -s "$BaseUrl/api/v1/vouchers/$voucherId" -H "Authorization: Bearer $admin" | ConvertFrom-Json
"Kuota akhir : $($after.remainingQuota) / $($after.totalQuota) (harus 0, tidak negatif)"
