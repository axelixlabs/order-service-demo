import type { Options } from 'k6/options';

import {
  type ApiConfig,
  exportOrdersCsv,
  getOrder,
  ordersFeed,
} from './api.ts';

// Read-only stress test that hammers the heavy endpoints with NO think-time so the
// N+1 / in-memory-pagination / OSIV-held-connection costs actually surface under a
// bounded (10-connection) pool. The dataset is not mutated, so both branches run
// against identical data.
const BASE_URL = __ENV.BASE_URL ?? 'http://localhost:8080';
const CUSTOMERS = parseInt(__ENV.CUSTOMERS ?? '10', 10);
const ORDERS = parseInt(__ENV.ORDERS ?? '20000', 10);
const FEED_SIZE = parseInt(__ENV.FEED_SIZE ?? '10', 10);
const PAGES = Math.max(1, Math.floor(ORDERS / CUSTOMERS / FEED_SIZE));
const FROM = '2020-01-01T00:00:00Z';

export const options: Options = {
  scenarios: {
    stress: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '1m', target: 40 },
        { duration: '1m', target: 60 },
        { duration: '30s', target: 60 },
      ],
    },
  },
};

function cfg(): ApiConfig {
  return { baseUrl: BASE_URL, feedFrom: FROM, feedSize: FEED_SIZE };
}

function rnd(n: number): number {
  return Math.floor(Math.random() * n);
}

function feedWindowTo(): string {
  return new Date(Date.now() + 86_400_000).toISOString();
}

export default function (): void {
  const c = cfg();
  const customer = rnd(CUSTOMERS) + 1;
  const to = feedWindowTo();

  // Heavy path: on main this loads ALL of the customer's orders into memory and
  // N+1s payment/shipment/customer/product per order, regardless of page.
  ordersFeed(c, customer, to, rnd(PAGES), 'stress_feed');

  // Hot path with the worst N+1 on main (items, products, customer, payment, shipment).
  getOrder(c, rnd(ORDERS) + 1, 'stress_get');

  // Occasional very heavy report: streams thousands of rows, N+1 per row on main.
  if (Math.random() < 0.1) {
    exportOrdersCsv(c, customer, to, 'stress_csv');
  }
}
