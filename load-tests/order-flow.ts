import { sleep } from 'k6';
import type { Options } from 'k6/options';

import {
  type ApiConfig,
  type CreateOrderPayload,
  createOrder,
  exportOrdersCsv,
  findLastPage,
  getOrder,
  ordersFeed,
  updateOrderStatus,
} from './api.ts';

const BASE_URL = __ENV.BASE_URL ?? 'http://localhost:8080';
const CUSTOMER_COUNT = parseInt(__ENV.CUSTOMER_COUNT ?? '100000', 10);
const FEED_FROM = __ENV.FEED_FROM ?? '2020-01-01T00:00:00Z';
const FEED_SIZE = parseInt(__ENV.FEED_SIZE ?? '10', 10);
const SEED_MODE = (__ENV.SEED_MODE ?? 'false') === 'true';

const PAYMENT_METHODS = ['CREDIT_CARD', 'DEBIT_CARD', 'PAYPAL', 'BANK_TRANSFER'] as const;

export const options: Options = {
  scenarios: {
    order_flow: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 15 },
        { duration: '1m', target: 23 },
        { duration: '1m', target: 45 },
        { duration: '1m', target: 23 },
        { duration: '1m', target: 15 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<10000'],
    checks: ['rate>0.95'],
  },
};

function apiConfig(): ApiConfig {
  return { baseUrl: BASE_URL, feedFrom: FEED_FROM, feedSize: FEED_SIZE };
}

function customerIdForVu(vu: number): number {
  if (SEED_MODE) {
    return 1;
  }
  return ((vu - 1) % CUSTOMER_COUNT) + 1;
}

function shippingAddressId(customerId: number): number {
  // Bulk loader: even address ids are SHIPPING. Demo seed: first address is SHIPPING (id 1).
  return SEED_MODE ? 1 : customerId * 2;
}

function productIdForVu(vu: number): number {
  return ((vu - 1) % 1000) + 1;
}

function feedWindowTo(): string {
  return new Date(Date.now() + 86_400_000).toISOString();
}

function buildCreatePayload(customerId: number, vu: number, line: number): CreateOrderPayload {
  const productId = ((productIdForVu(vu) + line - 1) % 1000) + 1;
  return {
    customerId,
    shippingAddressId: shippingAddressId(customerId),
    paymentMethod: PAYMENT_METHODS[(vu + line) % PAYMENT_METHODS.length],
    items: [{ productId, quantity: 1 + (line % 2) }],
  };
}

export default function (): void {
  const cfg = apiConfig();
  const customerId = customerIdForVu(__VU);
  const to = feedWindowTo();

  const lastPage = findLastPage(cfg, customerId, to);
  ordersFeed(cfg, customerId, to, lastPage, 'orders_feed_last_page');
  sleep(5);

  const preLastPage = Math.max(0, lastPage - 1);
  ordersFeed(cfg, customerId, to, preLastPage, 'orders_feed_pre_last_page');
  sleep(5);

  const created = [];
  for (let i = 1; i <= 3; i += 1) {
    const order = createOrder(cfg, buildCreatePayload(customerId, __VU, i), `create_order_${i}`);
    if (order) {
      created.push(order);
    }
    sleep(30);
  }

  for (let i = 0; i < created.length; i += 1) {
    updateOrderStatus(cfg, created[i].id, 'PAID', `status_paid_${i + 1}`);
  }
  sleep(10);

  if (created.length >= 2) {
    updateOrderStatus(cfg, created[1].id, 'PROCESSING', 'status_processing_second');
  }
  sleep(10);

  const lastOrder = created[created.length - 1];
  if (lastOrder) {
    getOrder(cfg, lastOrder.id, 'get_order_last');
  }
  sleep(3);

  if (lastOrder) {
    updateOrderStatus(cfg, lastOrder.id, 'CANCELLED', 'status_cancelled_last');
  }
  sleep(3);

  const refreshedLastPage = findLastPage(cfg, customerId, to);
  ordersFeed(cfg, customerId, to, refreshedLastPage, 'orders_feed_last_page_final');
  sleep(5);

  exportOrdersCsv(cfg, customerId, to, 'orders_csv_export');
}
