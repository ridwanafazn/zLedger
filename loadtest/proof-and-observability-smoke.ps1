<#
.SYNOPSIS
    Smoke test Fase J (bukti bayar / object storage) + Fase K (Prometheus & Grafana).

.DESCRIPTION
    1. checkout -> transactionId
    2. upload bukti bayar (PNG valid)          -> 201 + checksum SHA-256
    3. unduh isi file dan bandingkan hash      -> identik
    4. upload duplikat                          -> 400
    5. upload file palsu (png spoof)            -> 400
    6. customer lain mengunduh bukti orang lain -> 403
    7. customer menghapus bukti                 -> 403, ADMIN -> 204
    8. /actuator/prometheus memuat metrik bisnis
    9. Prometheus sudah scrape metrik tersebut
   10. Grafana punya datasource Prometheus + dashboard FinLedger

.EXAMPLE
    powershell -File loadtest/proof-and-observability-smoke.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PrometheusUrl = "http://localhost:9090",
    [string]$GrafanaUrl = "http://localhost:3000",
    [string]$GrafanaUser = "admin",
    [string]$GrafanaPassword = "admin"
)

$ErrorActionPreference = "Stop"
$tmp = Join-Path $env:TEMP "finledger-proof"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$failures = @()
function Check([string]$label, [bool]$ok, [string]$detail) {
    if ($ok) {
        Write-Host ("  [PASS] {0} {1}" -f $label, $detail) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0} {1}" -f $label, $detail) -ForegroundColor Red
        $script:failures += "$label $detail"
    }
}

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
    return @{ code = $code; body = (Get-Content (Join-Path $tmp "resp.json") -Raw) }
}

$admin = Login "admin" "admin123"
$customer = Login "customer" "customer123"

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 6).ToUpper()
$owner = "proof-smoke-$suffix"
$merchant = "proof-merchant-$suffix"

Write-Host "`n=== 1. Siapkan transaksi ===" -ForegroundColor Cyan
$r = Post-Json "/api/v1/ledger/accounts" $admin (@{
    ownerId = $owner; accountName = "Proof smoke"; accountType = "LIABILITY";
    currency = "IDR"; initialBalance = 50000
} | ConvertTo-Json -Compress) "proof-acc-$suffix"
Check "buat akun" ($r.code -eq "201") "HTTP $($r.code)"

$r = Post-Json "/api/v1/ledger/checkout" $admin (@{
    ownerId = $owner; merchantId = $merchant; totalAmount = 20000; currency = "IDR";
    idempotencyKey = "proof-co-$suffix"; storeId = "SMOKE";
    tenders = @(@{ currency = "IDR"; amount = 20000; description = "proof smoke" })
} | ConvertTo-Json -Compress -Depth 5) "proof-co-$suffix"
$checkout = $r.body | ConvertFrom-Json
$transactionId = $checkout.transactionId
Check "checkout" ($r.code -eq "200" -and [bool]$transactionId) "HTTP $($r.code) tx=$transactionId"

# PNG 1x1 valid
$pngPath = Join-Path $tmp "bukti.png"
[IO.File]::WriteAllBytes($pngPath, [Convert]::FromBase64String(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))

Write-Host "`n=== 2. Upload bukti bayar ===" -ForegroundColor Cyan
$respFile = Join-Path $tmp "upload.json"
$code = curl.exe -s -o $respFile -w "%{http_code}" -X POST "$BaseUrl/api/v1/payment-proofs" `
    -H "Authorization: Bearer $admin" `
    -F "transactionId=$transactionId" -F "file=@$pngPath;type=image/png"
$proof = Get-Content $respFile -Raw | ConvertFrom-Json
Check "upload -> 201" ($code -eq "201") "HTTP $code"
Check "checksum SHA-256 64 hex" ($proof.checksumSha256 -match '^[0-9a-f]{64}$') "checksum=$($proof.checksumSha256)"
Check "ukuran tercatat" ($proof.sizeBytes -gt 0) "size=$($proof.sizeBytes) byte"
Check "contentUrl tersedia" ([bool]$proof.contentUrl) "$($proof.contentUrl)"

Write-Host "`n=== 3. Unduh & verifikasi integritas ===" -ForegroundColor Cyan
$downPath = Join-Path $tmp "unduh.png"
$code = curl.exe -s -o $downPath -w "%{http_code}" "$BaseUrl$($proof.contentUrl)" -H "Authorization: Bearer $admin"
$srcHash = (Get-FileHash $pngPath -Algorithm SHA256).Hash
$dstHash = (Get-FileHash $downPath -Algorithm SHA256).Hash
Check "unduh -> 200" ($code -eq "200") "HTTP $code"
Check "hash unduhan identik" ($srcHash -eq $dstHash) "sha256=$($dstHash.Substring(0,16))..."

$listRaw = curl.exe -s "$BaseUrl/api/v1/payment-proofs?transactionId=$transactionId" -H "Authorization: Bearer $admin"
$list = $listRaw | ConvertFrom-Json
Check "daftar berisi 1 bukti" (@($list).Count -eq 1) "count=$(@($list).Count)"

Write-Host "`n=== 4. Validasi negatif ===" -ForegroundColor Cyan
$code = curl.exe -s -o NUL -w "%{http_code}" -X POST "$BaseUrl/api/v1/payment-proofs" `
    -H "Authorization: Bearer $admin" -F "transactionId=$transactionId" -F "file=@$pngPath;type=image/png"
Check "upload duplikat -> 400" ($code -eq "400") "HTTP $code"

$fakePath = Join-Path $tmp "palsu.png"
[IO.File]::WriteAllText($fakePath, "ini bukan gambar")
$code = curl.exe -s -o NUL -w "%{http_code}" -X POST "$BaseUrl/api/v1/payment-proofs" `
    -H "Authorization: Bearer $admin" -F "transactionId=$transactionId" -F "file=@$fakePath;type=image/png"
Check "signature palsu -> 400" ($code -eq "400") "HTTP $code"

$code = curl.exe -s -o NUL -w "%{http_code}" "$BaseUrl$($proof.contentUrl)" -H "Authorization: Bearer $customer"
Check "customer lain unduh -> 403" ($code -eq "403") "HTTP $code"

$code = curl.exe -s -o NUL -w "%{http_code}" -X DELETE "$BaseUrl/api/v1/payment-proofs/$($proof.id)" -H "Authorization: Bearer $customer"
Check "customer hapus -> 403" ($code -eq "403") "HTTP $code"

Write-Host "`n=== 5. Hapus oleh ADMIN ===" -ForegroundColor Cyan
$code = curl.exe -s -o NUL -w "%{http_code}" -X DELETE "$BaseUrl/api/v1/payment-proofs/$($proof.id)" -H "Authorization: Bearer $admin"
Check "admin hapus -> 204" ($code -eq "204") "HTTP $code"

Write-Host "`n=== 6. Metrik Prometheus (Fase K) ===" -ForegroundColor Cyan
$metricsRaw = curl.exe -s "$BaseUrl/actuator/prometheus"
$metrics = $metricsRaw -join "`n"
Check "endpoint /actuator/prometheus terbuka" ([bool]$metrics -and $metrics -notmatch '"status":401') "ok"
Check "metrik bukti bayar terdaftar" ([bool]($metrics -match 'finledger_payment_proof_uploaded_total')) "found"
Check "metrik HTTP server terdaftar" ([bool]($metrics -match 'http_server_requests_seconds_count')) "found"

Write-Host "  (menunggu Prometheus scrape berikutnya...)" -ForegroundColor DarkGray
Start-Sleep -Seconds 20
$query = [uri]::EscapeDataString('sum(finledger_payment_proof_uploaded_total)')
$promRaw = curl.exe -s "$PrometheusUrl/api/v1/query?query=$query"
$prom = $promRaw | ConvertFrom-Json
$value = if ($prom.data.result.Count -gt 0) { [double]$prom.data.result[0].value[1] } else { 0 }
Check "Prometheus scrape metrik bisnis" ($prom.status -eq "success" -and $value -ge 1) "value=$value"

$upRaw = curl.exe -s "$PrometheusUrl/api/v1/targets"
$up = ($upRaw | ConvertFrom-Json).data.activeTargets | Where-Object { $_.labels.job -eq "finledger-engine" }
Check "target finledger-engine UP" ($up.health -eq "up") "health=$($up.health)"

Write-Host "`n=== 7. Grafana provisioning ===" -ForegroundColor Cyan
$auth = "-u $($GrafanaUser):$($GrafanaPassword)"
$dsRaw = curl.exe -s $auth.Split(' ') "$GrafanaUrl/api/datasources"
$ds = $dsRaw | ConvertFrom-Json
$promDs = $ds | Where-Object { $_.type -eq "prometheus" }
Check "datasource Prometheus ter-provision" ($null -ne $promDs) "name=$($promDs.name)"

$dashRaw = curl.exe -s $auth.Split(' ') "$GrafanaUrl/api/search?query=FinLedger"
$dash = $dashRaw | ConvertFrom-Json
Check "dashboard FinLedger ter-provision" (@($dash).Count -ge 1) "count=$(@($dash).Count)"

Write-Host "`n=== 8. Bersihkan data smoke ===" -ForegroundColor Cyan
$sql = @"
CREATE TEMP TABLE smoke_journals AS
SELECT id FROM journal_transactions WHERE idempotency_key LIKE 'proof-co-%';
DELETE FROM ledger_entries WHERE transaction_id IN (SELECT id FROM smoke_journals);
DELETE FROM journal_transactions WHERE id IN (SELECT id FROM smoke_journals);
DELETE FROM accounts WHERE owner_id LIKE 'proof-smoke-%' OR owner_id LIKE 'proof-merchant-%';
"@
docker exec finledger-postgres psql -U finledger -d finledger -q -c $sql | Out-Null
Check "cleanup dieksekusi" ($LASTEXITCODE -eq 0) "psql exit=$LASTEXITCODE"

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "SEMUA SMOKE TEST J+K LULUS" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("GAGAL: {0}" -f ($failures -join "; ")) -ForegroundColor Red
    exit 1
}
