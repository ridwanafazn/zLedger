<#
.SYNOPSIS
    Smoke test presigned upload (file besar) + retensi bukti bayar.

.DESCRIPTION
    Membutuhkan instance aplikasi dengan STORAGE_TYPE=s3 (mis. port 8081) dan
    object storage S3-compatible pada STORAGE_ENDPOINT.

    Alur: presign -> PUT langsung ke storage -> complete (verifikasi checksum)
          -> unduh (hash sama) -> retention run(0 hari) -> metadata hilang.

.EXAMPLE
    powershell -File loadtest/presign-smoke.ps1 -BaseUrl http://localhost:8081
#>
param(
    [string]$BaseUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"
$tmp = Join-Path $env:TEMP "finledger-presign"
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
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 6).ToUpper()
$owner = "presign-smoke-$suffix"
$merchant = "presign-merchant-$suffix"

Write-Host "`n=== 1. Siapkan transaksi ===" -ForegroundColor Cyan
$r = Post-Json "/api/v1/ledger/accounts" $admin (@{
    ownerId = $owner; accountName = "Presign smoke"; accountType = "LIABILITY";
    currency = "IDR"; initialBalance = 50000
} | ConvertTo-Json -Compress) "presign-acc-$suffix"
Check "buat akun" ($r.code -eq "201") "HTTP $($r.code)"

$r = Post-Json "/api/v1/ledger/checkout" $admin (@{
    ownerId = $owner; merchantId = $merchant; totalAmount = 15000; currency = "IDR";
    idempotencyKey = "presign-co-$suffix"; storeId = "SMOKE";
    tenders = @(@{ currency = "IDR"; amount = 15000; description = "presign smoke" })
} | ConvertTo-Json -Compress -Depth 5) "presign-co-$suffix"
$transactionId = ($r.body | ConvertFrom-Json).transactionId
Check "checkout" ($r.code -eq "200" -and [bool]$transactionId) "tx=$transactionId"

# file 3 MB (lebih besar dari batas multipart 5 MB? tidak; ukuran hanya simulasi jalur presign)
$bigPath = Join-Path $tmp "bukti-besar.png"
$png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
$bytes = New-Object byte[] 3145728
[Array]::Copy($png, $bytes, $png.Length)
for ($i = $png.Length; $i -lt $bytes.Length; $i++) { $bytes[$i] = 0x20 }
[IO.File]::WriteAllBytes($bigPath, $bytes)
$size = (Get-Item $bigPath).Length
$localHash = (Get-FileHash $bigPath -Algorithm SHA256).Hash.ToLower()

Write-Host "`n=== 2. Presign ===" -ForegroundColor Cyan
$body = @{ transactionId = $transactionId; filename = "bukti-besar.png"; contentType = "image/png"; sizeBytes = $size } | ConvertTo-Json -Compress
$r = Post-Json "/api/v1/payment-proofs/presign" $admin $body $null
$presign = $r.body | ConvertFrom-Json
Check "presign -> 200" ($r.code -eq "200") "HTTP $($r.code)"
Check "uploadUrl tersedia" ([bool]$presign.uploadUrl) "$($presign.method) $($presign.uploadUrl.Substring(0, [Math]::Min(60, $presign.uploadUrl.Length)))..."

Write-Host "`n=== 3. PUT langsung ke storage ===" -ForegroundColor Cyan
$putCode = curl.exe -s -o NUL -w "%{http_code}" -X PUT "$($presign.uploadUrl)" `
    -H "Content-Type: image/png" --data-binary "@$bigPath"
Check "PUT ke S3 -> 2xx" ($putCode -match '^2') "HTTP $putCode"

Write-Host "`n=== 4. Complete (verifikasi server) ===" -ForegroundColor Cyan
$r = Post-Json "$($presign.completeUrl)" $admin '{}' $null
$proof = $r.body | ConvertFrom-Json
Check "complete -> UPLOADED" ($r.code -eq "200" -and $proof.status -eq "UPLOADED") "HTTP $($r.code) status=$($proof.status)"
Check "checksum server = hash lokal" ($proof.checksumSha256 -eq $localHash) "server=$($proof.checksumSha256.Substring(0,16))..."
Check "ukuran sesuai" ([long]$proof.sizeBytes -eq $size) "size=$($proof.sizeBytes)"

Write-Host "`n=== 5. Unduh & integritas ===" -ForegroundColor Cyan
$downPath = Join-Path $tmp "unduh.png"
$code = curl.exe -s -o $downPath -w "%{http_code}" "$BaseUrl$($proof.contentUrl)" -H "Authorization: Bearer $admin"
Check "unduh -> 200" ($code -eq "200") "HTTP $code"
Check "hash unduhan identik" ((Get-FileHash $downPath -Algorithm SHA256).Hash.ToLower() -eq $localHash) "ok"

Write-Host "`n=== 6. Retensi (0 hari) ===" -ForegroundColor Cyan
$r = Post-Json "/api/v1/payment-proofs/retention/run?olderThanDays=0" $admin '{}' $null
$retention = $r.body | ConvertFrom-Json
Check "retention menghapus >= 1" ($r.code -eq "200" -and [int]$retention.purgedProofs -ge 1) "purged=$($retention.purgedProofs)"

$code = curl.exe -s -o NUL -w "%{http_code}" "$BaseUrl/api/v1/payment-proofs/$($proof.id)" -H "Authorization: Bearer $admin"
Check "metadata setelah retensi -> 404" ($code -eq "404") "HTTP $code"

Write-Host "`n=== 7. Bersihkan data smoke ===" -ForegroundColor Cyan
$sql = @"
CREATE TEMP TABLE pj AS SELECT id FROM journal_transactions WHERE idempotency_key LIKE 'presign-co-%';
DELETE FROM ledger_entries WHERE transaction_id IN (SELECT id FROM pj);
DELETE FROM journal_transactions WHERE id IN (SELECT id FROM pj);
DELETE FROM accounts WHERE owner_id LIKE 'presign-smoke-%' OR owner_id LIKE 'presign-merchant-%';
"@
docker exec finledger-postgres psql -U finledger -d finledger -q -c $sql | Out-Null
Check "cleanup dieksekusi" ($LASTEXITCODE -eq 0) "psql exit=$LASTEXITCODE"

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "SEMUA SMOKE PRESIGN + RETENSI LULUS" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("GAGAL: {0}" -f ($failures -join "; ")) -ForegroundColor Red
    exit 1
}
