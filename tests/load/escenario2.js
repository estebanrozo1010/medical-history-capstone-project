// Escenario 2 de docs/05 adaptado al lab (Tarea 18). k6 run tests/load/escenario2.js
// - mezcla: 8 req/s durante 3 min (bajo el throttling de 10/s) sobre GET /s/{token} y /s/{token}/files/{fileId} con los
//   links de `datos.mjs preparar`: de 50 y de 1 archivo (200 / 302), apagado, vencido e inventado (410). Sin 429.
// - rafaga: 30 req/s durante 20 s sobre un token inventado, solo para ver que el exceso responde 429 y nunca 5xx.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const API = __ENV.MH_API || 'https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1';
const datos = JSON.parse(open(`${__ENV.TEMP}/mh-carga.json`));
const INVENTADO = 'Z'.repeat(43);
const respuestas5xx = new Counter('respuestas_5xx');
const respuestas429 = new Counter('respuestas_429');

const P95 = ['p(95)<500'];
export const options = {
  scenarios: {
    mezcla: { executor: 'constant-arrival-rate', exec: 'mezcla', rate: 8, timeUnit: '1s', duration: '3m', preAllocatedVUs: 20, maxVUs: 20 },
    rafaga: { executor: 'constant-arrival-rate', exec: 'rafaga', rate: 30, timeUnit: '1s', duration: '20s', preAllocatedVUs: 30, maxVUs: 30, startTime: '3m10s' },
  },
  maxRedirects: 0, // el 302 se mide sin bajar el archivo de S3
  thresholds: {
    'http_req_duration{scenario:mezcla}': ['p(95)<500'],
    'http_req_duration{caso:lista50}': P95,
    'http_req_duration{caso:lista1}': P95,
    'http_req_duration{caso:descarga50}': P95,
    'http_req_duration{caso:apagado}': P95,
    'http_req_duration{caso:vencido}': P95,
    'http_req_duration{caso:inventado}': P95,
    'checks{scenario:mezcla}': ['rate==1'],
    'checks{scenario:rafaga}': ['rate==1'],
    respuestas_5xx: ['count==0'],
    'respuestas_429{scenario:mezcla}': ['count==0'],
    'respuestas_429{scenario:rafaga}': ['count>0'],
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max', 'avg'],
};

const al = (lista) => lista[Math.floor(Math.random() * lista.length)];
// [peso, caso, ruta, código esperado]; apagado y vencido alternan la lista y la descarga
const CASOS = [
  [30, 'lista50', () => `/s/${datos.l50}`, 200],
  [15, 'lista1', () => `/s/${datos.l1}`, 200],
  [25, 'descarga50', () => `/s/${datos.l50}/files/${al(datos.ids)}`, 302],
  [10, 'apagado', () => al([`/s/${datos.apagado}`, `/s/${datos.apagado}/files/${datos.ids[0]}`]), 410],
  [10, 'vencido', () => al([`/s/${datos.vencido}`, `/s/${datos.vencido}/files/${datos.ids[0]}`]), 410],
  [10, 'inventado', () => al([`/s/${INVENTADO}`, `/s/${INVENTADO}/files/${datos.ids[0]}`]), 410],
];
const ARCHIVOS = { lista50: 50, lista1: 1 };

function contar(r) {
  respuestas5xx.add(r.status >= 500 ? 1 : 0);
  respuestas429.add(r.status === 429 ? 1 : 0);
}

export function mezcla() {
  let n = Math.random() * 100;
  const [, nombre, ruta, esperado] = CASOS.find(([peso]) => (n -= peso) < 0);
  const r = http.get(API + ruta(), { tags: { caso: nombre, name: nombre }, responseCallback: http.expectedStatuses(esperado) });
  contar(r);
  check(r, {
    [`${nombre} -> ${esperado}`]: (x) => x.status === esperado,
    [`${nombre}: cuerpo correcto`]: (x) =>
      esperado === 200 ? x.json('archivos').length === ARCHIVOS[nombre]
        : esperado === 302 ? String(x.headers.Location).includes('X-Amz-Expires=15')
          : x.json('code') === 'NO_DISPONIBLE',
  });
}

export function rafaga() {
  const r = http.get(`${API}/s/${INVENTADO}`, { tags: { caso: 'rafaga', name: 'rafaga' }, responseCallback: http.expectedStatuses(410, 429) });
  contar(r);
  check(r, { 'rafaga -> 410 o 429': (x) => x.status === 410 || x.status === 429 });
}
