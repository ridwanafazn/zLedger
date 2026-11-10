<#
.SYNOPSIS
    Harness konkurensi lokal untuk FinLedger Engine (pengganti sementara k6).

.DESCRIPTION
    Menjalankan dua skenario memakai curl --parallel:
      1. Flash sale race: N redeem paralel pada saldo terbatas.
      2. Retry storm: N checkout identik dengan satu idempotency key.

    Prasyarat: aplikasi berjalan di http://localhost:8080 dan container
    finledger-postgres aktif (untuk verifikasi jumlah jurnal).

.EXAMPLE
    powershell -File loadtest/local-loadtest.ps1 -Parallel 50
#>
param(
    [int]$Parallel = 50,
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$tmp = Join-Path $env:TEMP "finledger-loadtest"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Login([string]$user, [string]$pass) {
    $file = Join-Path $tmp "login.json"
    [IO.File]::WriteAllText($file, "{`"username`":`"$user`",`"password`":`"$pass`"}")
    $raw = curl.exe -s -X POST "$BaseUrl/api/v1/auth/login" -H "Content-Type: application/json" --data-binary "@$file"
    return ($raw | ConvertFrom-Json).accessToken
}

function CreateAccount([string]$token, [string]$owner, [string]$currency, [int]$balance) {
    $file = Join-Path $tmp "account.json"
    $body = @{ ownerId = $owner; accountName = "load wallet"; accountType = "LIABILITY"; currency = $currency; initialBalance = $balance } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($file, $body)
    $code = curl.exe -s -o NUL -w "%{http_code}" -X POST "$BaseUrl/api/v1/ledger/accounts" `
        -H "Authorization: Bearer $token" -H "Content-Type: application/json" `
        -H "X-Idempotency-Key: load-acc-$owner" --data-binary "@$file"
    if ($code -ne "201") { throw "Gagal membuat akun ($code)" }
}

function RunParallel([string]$configFile) {
    return curl.exe -s --parallel --parallel-max $Parallel --config $configFile
}

function ParseCodes([string[]]$lines) {
    $codes = @{}
    $times = @()
    foreach ($line in $lines) {
        if ($line -match 'code:(\d{3}) time:([0-9.]+)') {
            $code = $matches[1]
            if ($codes.ContainsKey($code)) { $codes[$code]++ } else { $codes[$code] = 1 }
            $times += [double]$matches[2]
        }
    }
    return @{ Codes = $codes; Times = $times }
}

function GetBalance([string]$token, [string]$owner, [string]$currency) {
    $raw = curl.exe -s "$BaseUrl/api/v1/ledger/accounts?ownerId=$owner&currency=$currency" -H "Authorization: Bearer $token"
    return (($raw | ConvertFrom-Json).content[0]).balance
}

function ShowLatency([double[]]$times) {
    if ($times.Count -eq 0) { return "n/a" }
    $sorted = $times | Sort-Object
    $p95Index = [Math]::Min($sorted.Count - 1, [Math]::Ceiling($sorted.Count * 0.95) - 1)
    $avg = ($times | Measure-Object -Average).Average * 1000
    return ("avg={0:N1} ms, p95={1:N1} ms, max={2:N1} ms" -f $avg, ($sorted[$p95Index] * 1000), ($sorted[-1] * 1000))
}

$admin = Login "admin" "admin123"
$customer = Login "customer" "customer123"

$runId = [guid]::NewGuid().ToString("N").Substring(0, 6)

Write-Host "=== Skenario 1: Flash sale race ($Parallel redeem paralel) ===" -ForegroundColor Cyan
$owner1 = "load-flash-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
CreateAccount $admin $owner1 "A_POIN" 1000
$voucher = [guid]::NewGuid()
$cfg = Join-Path $tmp "flash.cfg"
$lines = @(
    "header = `"Authorization: Bearer $customer`"",
    "request = `"POST`"",
    "output = `"NUL`"",
    "write-out = `"code:%{http_code} time:%{time_total}\n`""
)
for ($i = 0; $i -lt $Parallel; $i++) {
    $lines += "url = `"$BaseUrl/api/v1/ledger/redeem?ownerId=$owner1&voucherId=$voucher&points=100&idempotencyKey=flash-$runId-$i`""
}
Set-Content -Path $cfg -Value $lines -Encoding ASCII
$sw = [Diagnostics.Stopwatch]::StartNew()
$out = RunParallel $cfg
$sw.Stop()
$parsed = ParseCodes ($out -split "`n")
"Kode HTTP : " + (($parsed.Codes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ", ")
"Durasi total: {0:N2} s" -f $sw.Elapsed.TotalSeconds
"Latensi   : $(ShowLatency $parsed.Times)"
"Saldo akhir A_POIN: $(GetBalance $admin $owner1 'A_POIN') (tidak boleh negatif)"

Write-Host "`n=== Skenario 2: Retry storm idempotency ($Parallel request identik) ===" -ForegroundColor Cyan
$owner2 = "load-idem-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
CreateAccount $admin $owner2 "IDR" 100000
$key = "load-key-$runId-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
$cfg2 = Join-Path $tmp "idem.cfg"
$body = @{ ownerId = $owner2; merchantId = "load-merchant"; totalAmount = 10000; currency = "IDR"; idempotencyKey = $key; tenders = @(@{ currency = "IDR"; amount = 10000; description = "retry storm" }) } | ConvertTo-Json -Depth 5 -Compress
$bodyFile = Join-Path $tmp "checkout.json"
[IO.File]::WriteAllText($bodyFile, $body)
$bodyFileCurl = $bodyFile.Replace('\', '/')
$lines2 = @(
    "header = `"Authorization: Bearer $customer`"",
    "header = `"Content-Type: application/json`"",
    "header = `"X-Idempotency-Key: $key`"",
    "request = `"POST`"",
    "data-binary = `"@$bodyFileCurl`"",
    "output = `"NUL`"",
    "write-out = `"code:%{http_code} time:%{time_total}\n`""
)
for ($i = 0; $i -lt $Parallel; $i++) { $lines2 += "url = `"$BaseUrl/api/v1/ledger/checkout`"" }
Set-Content -Path $cfg2 -Value $lines2 -Encoding ASCII
$sw2 = [Diagnostics.Stopwatch]::StartNew()
$out2 = RunParallel $cfg2
$sw2.Stop()
$parsed2 = ParseCodes ($out2 -split "`n")
"Kode HTTP : " + (($parsed2.Codes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ", ")
"Durasi total: {0:N2} s" -f $sw2.Elapsed.TotalSeconds
"Latensi   : $(ShowLatency $parsed2.Times)"
"Saldo akhir IDR: $(GetBalance $admin $owner2 'IDR') (harus 90000)"
$journalCount = docker exec finledger-postgres psql -U finledger -d finledger -t -A -c "select count(*) from journal_transactions where idempotency_key='$key';"
"Jumlah jurnal untuk key tersebut: $journalCount (harus 1)"
