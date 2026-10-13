// Escenario 1 de docs/05 adaptado al lab (Tarea 18): POST /files/upload-url a 8 req/s fijos durante 60 s, con hasta
// 20 usuarios virtuales (el stage corta en 10 req/s). k6 run tests/load/escenario1.js
// Cada petición crea un registro PENDING "carga-t18-e1.pdf"; los borra `node tests/load/datos.mjs limpiar`.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const API = __ENV.MH_API || 'https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1';
const TOKEN = open(`${__ENV.TEMP}/mh-token.txt`).trim();
const respuestas5xx = new Counter('respuestas_5xx');
const respuestas429 = new Counter('respuestas_429');

export const options = {
  scenarios: {
    upload_url: { executor: 'constant-arrival-rate', rate: 8, timeUnit: '1s', duration: '60s', preAllocatedVUs: 20, maxVUs: 20 },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],
    respuestas_5xx: ['count==0'],
    respuestas_429: ['count==0'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max', 'avg'],
};

const body = JSON.stringify({ carpeta: 'otros', nombre: 'carga-t18-e1.pdf', contentType: 'application/pdf', size: 1024 });
const params = {
  headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
  responseCallback: http.expectedStatuses(201),
};

export default function () {
  const r = http.post(`${API}/files/upload-url`, body, params);
  respuestas5xx.add(r.status >= 500 ? 1 : 0);
  respuestas429.add(r.status === 429 ? 1 : 0);
  check(r, { 'upload-url -> 201 con url firmada': (x) => x.status === 201 && String(x.json('url')).startsWith('https://') });
}
