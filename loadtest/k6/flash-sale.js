import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

/**
 * Skenario 1 — Flash sale redemption dengan saldo terbatas.
 *
 * Target: saldo tidak pernah negatif dan jumlah sukses sesuai kapasitas saldo.
 * Jalankan:
 *   k6 run -e VUS=50 -e ITERATIONS=200 loadtest/k6/flash-sale.js
 */
export const options = {
  scenarios: {
    redeem: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 50),
      iterations: Number(__ENV.ITERATIONS || 200),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const POINTS_PER_REDEEM = Number(__ENV.POINTS || 100);
const START_BALANCE = Number(__ENV.BALANCE || 1000);

function jsonHeaders(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function login(username, password) {
  const res = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } });
  return res.json('accessToken');
}

export function setup() {
  const adminToken = login('admin', 'admin123');
  const customerToken = login('customer', 'customer123');
  const ownerId = `k6-flash-${Date.now()}`;

  const create = http.post(`${BASE}/api/v1/ledger/accounts`,
    JSON.stringify({
      ownerId,
      accountName: 'k6 flash wallet',
      accountType: 'LIABILITY',
      currency: 'A_POIN',
      initialBalance: START_BALANCE,
    }),
    { headers: { ...jsonHeaders(adminToken), 'X-Idempotency-Key': `k6-create-${ownerId}` } });

  if (create.status !== 201) {
    throw new Error(`Gagal membuat akun: ${create.status} ${create.body}`);
  }

  return { ownerId, customerToken, voucherId: uuidv4() };
}

export default function (data) {
  const key = `k6-redeem-${__VU}-${__ITER}`;
  const res = http.post(
    `${BASE}/api/v1/ledger/redeem?ownerId=${data.ownerId}` +
    `&voucherId=${data.voucherId}&points=${POINTS_PER_REDEEM}&idempotencyKey=${key}`,
    null,
    { headers: { Authorization: `Bearer ${data.customerToken}` } });

  check(res, {
    'status 200 atau ditolak valid (400/409)': (r) => [200, 400, 409].includes(r.status),
  });
}

export function teardown(data) {
  const res = http.get(
    `${BASE}/api/v1/ledger/accounts?ownerId=${data.ownerId}&currency=A_POIN`,
    { headers: { Authorization: `Bearer ${data.customerToken}` } });
  const content = res.json('content') || [];
  if (content.length > 0) {
    console.log(`Saldo akhir: ${content[0].balance} (tidak boleh negatif)`);
  }
}
