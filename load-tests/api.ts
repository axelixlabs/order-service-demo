import http, { type RefinedResponse, type ResponseType } from 'k6/http';
import { check } from 'k6';

export interface OrderLine {
  productId: number;
  quantity: number;
}

export interface CreateOrderPayload {
  customerId: number;
  shippingAddressId: number;
  paymentMethod: string;
  items: OrderLine[];
}

export interface OrderResponse {
  id: number;
  orderNumber: string;
  status: string;
  customerId: number;
  createdAt: string;
}

export interface ApiConfig {
  baseUrl: string;
  feedFrom: string;
  feedSize: number;
}

function jsonHeaders(): Record<string, string> {
  return { 'Content-Type': 'application/json' };
}

function checkOk(
  res: RefinedResponse<ResponseType>,
  name: string,
  expectedStatus = 200,
): boolean {
  return check(res, {
    [`${name} status ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });
}

export function ordersFeed(
  cfg: ApiConfig,
  customerId: number,
  to: string,
  page: number,
  tag: string,
): OrderResponse[] {
  const url =
    `${cfg.baseUrl}/api/v1/reports/orders` +
    `?customerId=${customerId}` +
    `&from=${encodeURIComponent(cfg.feedFrom)}` +
    `&to=${encodeURIComponent(to)}` +
    `&page=${page}` +
    `&size=${cfg.feedSize}`;

  const res = http.get(url, { tags: { name: tag } });
  checkOk(res, tag);
  return res.json() as unknown as OrderResponse[];
}

/** Walk pages until an empty one; return the last non-empty page index. */
export function findLastPage(cfg: ApiConfig, customerId: number, to: string): number {
  let page = 0;
  let lastNonEmpty = 0;

  while (true) {
    const orders = ordersFeed(cfg, customerId, to, page, 'orders_feed_probe');
    if (!orders.length) {
      break;
    }
    lastNonEmpty = page;
    if (orders.length < cfg.feedSize) {
      break;
    }
    page += 1;
  }

  return lastNonEmpty;
}

export function createOrder(
  cfg: ApiConfig,
  payload: CreateOrderPayload,
  tag: string,
): OrderResponse | null {
  const res = http.post(
    `${cfg.baseUrl}/api/v1/orders`,
    JSON.stringify(payload),
    { headers: jsonHeaders(), tags: { name: tag } },
  );
  if (!checkOk(res, tag, 201)) {
    return null;
  }
  return res.json() as unknown as OrderResponse;
}

export function getOrder(cfg: ApiConfig, orderId: number, tag: string): OrderResponse | null {
  const res = http.get(`${cfg.baseUrl}/api/v1/orders/${orderId}`, { tags: { name: tag } });
  if (!checkOk(res, tag)) {
    return null;
  }
  return res.json() as unknown as OrderResponse;
}

export function updateOrderStatus(
  cfg: ApiConfig,
  orderId: number,
  status: string,
  tag: string,
): OrderResponse | null {
  const res = http.patch(
    `${cfg.baseUrl}/api/v1/orders/${orderId}/status`,
    JSON.stringify({ status }),
    { headers: jsonHeaders(), tags: { name: tag } },
  );
  if (!checkOk(res, tag)) {
    return null;
  }
  return res.json() as unknown as OrderResponse;
}

export function exportOrdersCsv(
  cfg: ApiConfig,
  customerId: number,
  to: string,
  tag: string,
): boolean {
  const url =
    `${cfg.baseUrl}/api/v1/reports/orders/export` +
    `?customerId=${customerId}` +
    `&from=${encodeURIComponent(cfg.feedFrom)}` +
    `&to=${encodeURIComponent(to)}`;

  const res = http.get(url, { tags: { name: tag } });
  return check(res, {
    [`${tag} status 200`]: (r) => r.status === 200,
    [`${tag} csv body`]: (r) =>
      (r.headers['Content-Type'] ?? '').includes('text/csv') && (r.body as string).length > 0,
  });
}
