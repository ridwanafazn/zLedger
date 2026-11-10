<#
.SYNOPSIS
    Smoke test end-to-end settlement merchant + rekonsiliasi via HTTP.

.DESCRIPTION
    Membuktikan alur nyata pada aplikasi yang sedang berjalan:
      1. checkout merchant -> jurnal PAYMENT
      2. preview settlement (gross, fee 1%, net)
      3. create batch PENDING + replay idempotent + tolak periode bertumpuk (409)
      4. payout -> jurnal SETTLEMENT, tolak payout ganda (409)
      5. saldo merchant berkurang tepat sebesar net
      6. jalankan rekonsiliasi -> BALANCED
      7. bersihkan data smoke lalu rekonsiliasi ulang -> tetap BALANCED

.EXAMPLE
    powershell -File loadtest/settlement-smoke.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Amount = 40000
)

$ErrorActionPreference = "Stop"
$tmp = Join-Path $env:TEMP "finledger-settlement"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Login([string]$user, [string]$pass) {
    $file = Join-Path $tmp "login.json"
    [IO.File]::WriteAllText($file, "{`"username`":`"$user`",`"password`":`"$pass`"}")
    $raw = curl.exe -s -X POST "$BaseUrl/api/v1/auth/login" -H "Content-Type: application/json" --data-binary "@$file"
    return ($raw | ConvertFrom-Json).accessToken
}

function Post-Json([string]$path, [string]$token, [string]$body, [string]$idem) {
    $file = Join-Path $tmp "body.json"
    [IO.File]::WriteAllText($file, $body)
    $headers = @("-H", "Authorization: Bearer $token", "-H", "Content-Type: application/json")
    if ($idem) { $headers += @("-H", "X-Idempotency-Key: $idem") }
    $code = curl.exe -s -o (Join-Path $tmp "resp.json") -w "%{http_code}" -X POST "$BaseUrl$path" @headers --data-binary "@$file"
    $resp = Get-Content (Join-Path $tmp "resp.json") -Raw
    return @{ code = $code; body = $resp }
}

$failures = @()
function Check([string]$label, [bool]$ok, [string]$detail) {
    if ($ok) {
        Write-Host ("  [PASS] {0} {1}" -f $label, $detail) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0} {1}" -f $label, $detail) -ForegroundColor Red
        $script:failures += "$label $detail"
    }
}

$admin = Login "admin" "admin123"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 6).ToUpper()
$customer = "smoke-cust-$suffix"
$merchant = "smoke-merch-$suffix"
$now = Get-Date
$from = $now.AddMinutes(-10).ToString("yyyy-MM-ddTHH:mm:ss")
$to = $now.AddMinutes(10).ToString("yyyy-MM-ddTHH:mm:ss")

Write-Host "`n=== 1. Siapkan akun & transaksi ===" -ForegroundColor Cyan
$r = Post-Json "/api/v1/ledger/accounts" $admin (@{
    ownerId = $customer; accountName = "Smoke customer"; accountType = "LIABILITY";
    currency = "IDR"; initialBalance = 100000
} | ConvertTo-Json -Compress) "smoke-acc-$suffix"
Check "buat akun customer" ($r.code -eq "201") "HTTP $($r.code)"

$r = Post-Json "/api/v1/ledger/checkout" $admin (@{
    ownerId = $customer; merchantId = $merchant; totalAmount = $Amount; currency = "IDR";
    idempotencyKey = "smoke-co-$suffix"; storeId = "SMOKE";
    tenders = @(@{ currency = "IDR"; amount = $Amount; description = "smoke settlement" })
} | ConvertTo-Json -Compress -Depth 5) "smoke-co-$suffix"
Check "checkout merchant" ($r.code -eq "200" -or $r.code -eq "201") "HTTP $($r.code)"

Write-Host "`n=== 2. Preview settlement ===" -ForegroundColor Cyan
$previewUrl = "$BaseUrl/api/v1/settlements/preview?merchantId=$merchant&currency=IDR&from=$from&to=$to"
$preview = curl.exe -s "$previewUrl" -H "Authorization: Bearer $admin" | ConvertFrom-Json
$expectedFee = [math]::Round($Amount * 0.01, 4)
$expectedNet = $Amount - $expectedFee
Check "gross = $Amount" ([decimal]$preview.grossAmount -eq [decimal]$Amount) "gross=$($preview.grossAmount)"
Check "fee 1% = $expectedFee" ([decimal]$preview.feeAmount -eq [decimal]$expectedFee) "fee=$($preview.feeAmount)"
Check "net = $expectedNet" ([decimal]$preview.netAmount -eq [decimal]$expectedNet) "net=$($preview.netAmount)"

Write-Host "`n=== 3. Create batch (idempotent) ===" -ForegroundColor Cyan
$body = @{ merchantId = $merchant; currency = "IDR"; periodStart = $from; periodEnd = $to } | ConvertTo-Json -Compress
$r1 = Post-Json "/api/v1/settlements" $admin $body "smoke-set-$suffix"
$created = $r1.body | ConvertFrom-Json
Check "create -> 201 PENDING" ($r1.code -eq "201" -and $created.status -eq "PENDING") "HTTP $($r1.code) status=$($created.status)"

$r2 = Post-Json "/api/v1/settlements" $admin $body "smoke-set-$suffix"
$replay = $r2.body | ConvertFrom-Json
Check "replay key sama -> batch sama" ($replay.id -eq $created.id) "id=$($replay.id)"

$r3 = Post-Json "/api/v1/settlements" $admin $body "smoke-set-other-$suffix"
Check "periode bertumpuk -> 409" ($r3.code -eq "409") "HTTP $($r3.code)"

Write-Host "`n=== 4. Payout ===" -ForegroundColor Cyan
$settlementId = $created.id
$r = Post-Json "/api/v1/settlements/$settlementId/pay" $admin '{}' "smoke-pay-$suffix"
$paid = $r.body | ConvertFrom-Json
Check "pay -> PAID" ($r.code -eq "200" -and $paid.status -eq "PAID") "HTTP $($r.code) status=$($paid.status)"
Check "payoutTransactionId terisi" ([bool]$paid.payoutTransactionId) "payout=$($paid.payoutTransactionId)"

$r = Post-Json "/api/v1/settlements/$settlementId/pay" $admin '{}' "smoke-pay-again-$suffix"
Check "payout ganda -> 409" ($r.code -eq "409") "HTTP $($r.code)"

$detail = curl.exe -s "$BaseUrl/api/v1/settlements/$settlementId" -H "Authorization: Bearer $admin" | ConvertFrom-Json
Check "detail memuat item jurnal" ($detail.items.Count -ge 1) "items=$($detail.items.Count)"

Write-Host "`n=== 5. Saldo merchant ===" -ForegroundColor Cyan
$accounts = curl.exe -s "$BaseUrl/api/v1/ledger/accounts?ownerId=$merchant" -H "Authorization: Bearer $admin" | ConvertFrom-Json
$merchantBalance = [decimal]$accounts.content[0].balance
Check "saldo merchant = $expectedFee" ($merchantBalance -eq [decimal]$expectedFee) "balance=$merchantBalance"

Write-Host "`n=== 6. Rekonsiliasi ===" -ForegroundColor Cyan
$r = Post-Json "/api/v1/reconciliation/runs" $admin '{"notes":"smoke-smoke"}' $null
$run = $r.body | ConvertFrom-Json
Check "rekonsiliasi BALANCED" ($run.status -eq "BALANCED") "HTTP $($r.code) status=$($run.status) temuan=$($run.discrepancyCount)"

Write-Host "`n=== 7. Bersihkan data smoke ===" -ForegroundColor Cyan
$sql = @"
CREATE TEMP TABLE smoke_journals AS
SELECT id FROM journal_transactions WHERE idempotency_key LIKE 'smoke-%'
UNION SELECT transaction_id FROM ledger_entries WHERE account_id IN (SELECT id FROM accounts WHERE owner_id LIKE 'smoke-%')
UNION SELECT payout_transaction_id FROM merchant_settlements WHERE merchant_id LIKE 'smoke-%' AND payout_transaction_id IS NOT NULL;
DELETE FROM settlement_items WHERE settlement_id IN (SELECT id FROM merchant_settlements WHERE merchant_id LIKE 'smoke-%');
DELETE FROM merchant_settlements WHERE merchant_id LIKE 'smoke-%';
DELETE FROM ledger_entries WHERE transaction_id IN (SELECT id FROM smoke_journals);
DELETE FROM journal_transactions WHERE id IN (SELECT id FROM smoke_journals);
DELETE FROM accounts WHERE owner_id LIKE 'smoke-%' OR owner_id = 'system-settlement';
DELETE FROM reconciliation_discrepancies WHERE run_id IN (SELECT id FROM reconciliation_runs WHERE notes = 'smoke-smoke');
DELETE FROM reconciliation_runs WHERE notes = 'smoke-smoke';
"@
docker exec finledger-postgres psql -U finledger -d finledger -q -c $sql | Out-Null
Check "cleanup dieksekusi" ($LASTEXITCODE -eq 0) "psql exit=$LASTEXITCODE"

$r = Post-Json "/api/v1/reconciliation/runs" $admin '{}' $null
$finalRun = $r.body | ConvertFrom-Json
Check "rekonsiliasi setelah cleanup BALANCED" ($finalRun.status -eq "BALANCED") "status=$($finalRun.status) temuan=$($finalRun.discrepancyCount)"

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "SEMUA SMOKE TEST LULUS" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("GAGAL: {0}" -f ($failures -join "; ")) -ForegroundColor Red
    exit 1
}
