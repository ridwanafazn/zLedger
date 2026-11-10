import http from 'k6/http';
import { check } from 'k6';

/**
 * Skenario 2 — Retry storm idempotency.
 *
 * Semua iterasi memakai kunci idempotency yang SAMA. Ekspektasi:
 * - Semua request dibalas 200 (dari cache) atau 409 (masih diproses), tidak pernah 500.
 * - Hanya satu transaksi jurnal terbentuk dan saldo terpotong sekali.
 *
 * Jalankan:
 *   k6 run -e VUS=20 -e ITERATIONS=20 loadtest/k6/idempotency.js
 */
export const options = {
  scenarios: {
    retry_storm: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 20),
      iterations: Number(__ENV.ITERATIONS || 20),
      maxDuration: '1m',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

function login(username, password) {
  const res = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } });
  return res.json('accessToken');
}

export function setup() {
  const adminToken = login('admin', 'admin123');
  const customerToken = login('customer', 'customer123');
  const ownerId = `k6-idem-${Date.now()}`;
  const idempotencyKey = `k6-idem-key-${Date.now()}`;

  http.post(`${BASE}/api/v1/ledger/accounts`,
    JSON.stringify({
      ownerId,
      accountName: 'k6 idempotency wallet',
      accountType: 'LIABILITY',
      currency: 'IDR',
      initialBalance: 100000,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}`, 'X-Idempotency-Key': `k6-acc-${ownerId}` } });

  return { ownerId, customerToken, idempotencyKey };
}

export default function (data) {
  const body = JSON.stringify({
    ownerId: data.ownerId,
    merchantId: 'k6-merchant',
    totalAmount: 10000,
    currency: 'IDR',
    idempotencyKey: data.idempotencyKey,
    tenders: [{ currency: 'IDR', amount: 10000, description: 'retry storm' }],
  });

  const res = http.post(`${BASE}/api/v1/ledger/checkout`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${data.customerToken}`,
      'X-Idempotency-Key': data.idempotencyKey,
    },
  });

  check(res, {
    'tidak ada 500': (r) => r.status !== 500,
    '200 atau 409': (r) => [200, 409].includes(r.status),
  });
}
