// Datos de la prueba de carga (Tarea 18): node tests/load/datos.mjs preparar | limpiar
// Usa el ID token de %TEMP%\mh-token.txt (infra/id-token.mjs). Solo crea y borra archivos "carga-t18-*" de la carpeta
// "otros" (PDF falsos de ~60 bytes); los tokens de los links quedan en %TEMP%\mh-carga.json, fuera del repo.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const API = process.env.MH_API ?? 'https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1';
const TOKEN = readFileSync(join(process.env.TEMP, 'mh-token.txt'), 'ascii').trim();
const ESTADO = join(process.env.TEMP, 'mh-carga.json');
const PREFIJO = 'carga-t18-';
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

// El stage admite 10 req/s en total: aquí van ~4 req/s en serie, reintentando 429 y el 409 de la réplica en curso
let ultima = 0;
async function api(metodo, ruta, body, conToken = true) {
  for (let intento = 0; ; intento++) {
    await espera(Math.max(0, ultima + 250 - Date.now()));
    ultima = Date.now();
    const r = await fetch(API + ruta, {
      method: metodo,
      headers: { ...(conToken && { Authorization: `Bearer ${TOKEN}` }), ...(body && { 'Content-Type': 'application/json' }) },
      body: body && JSON.stringify(body),
    });
    if ((r.status === 429 || (r.status === 409 && metodo === 'DELETE')) && intento < 8) {
      await espera(2000);
      continue;
    }
    const texto = await r.text();
    return { status: r.status, json: texto ? JSON.parse(texto) : null };
  }
}

async function ok(metodo, ruta, body) {
  const r = await api(metodo, ruta, body);
  if (r.status >= 300) throw new Error(`${metodo} ${ruta.slice(0, 24)} -> ${r.status} ${JSON.stringify(r.json)}`);
  return r.json;
}

async function preparar() {
  const pdf = Buffer.from('%PDF-1.4\n% prueba de carga Tarea 18, datos falsos\n%%EOF\n');
  const ids = [];
  for (let i = 1; i <= 50; i++) {
    const nombre = `${PREFIJO}${String(i).padStart(2, '0')}.pdf`;
    const s = await ok('POST', '/files/upload-url', { carpeta: 'otros', nombre, contentType: 'application/pdf', size: pdf.length });
    const { 'content-length': _, host: __, ...headers } = Object.fromEntries(Object.entries(s.headers).map(([k, v]) => [k.toLowerCase(), v]));
    const put = await fetch(s.url, { method: 'PUT', headers, body: pdf });
    if (put.status !== 200) throw new Error(`PUT a S3 de ${nombre} -> ${put.status} ${await put.text()}`);
    ids.push(s.fileId);
  }
  console.log('50 PDF subidos; esperando a mh-postscan');
  for (let t = 0; ; t++) {
    const limpios = new Set((await ok('GET', '/files?carpeta=otros')).filter((a) => a.status === 'CLEAN').map((a) => a.fileId));
    const faltan = ids.filter((id) => !limpios.has(id)).length;
    if (!faltan) break;
    if (t === 40) throw new Error(`${faltan} archivos no llegaron a CLEAN`);
    await espera(3000);
  }
  const link = (fileIds, ttlMinutos) => ok('POST', '/links', { fileIds, ttlMinutos });
  const vencido = await link([ids[0]], 1);
  const estado = {
    ids,
    l50: (await link(ids, 120)).token,
    l1: (await link([ids[0]], 120)).token,
    apagado: (await link([ids[0]], 120)).token,
    vencido: vencido.token,
  };
  await ok('PATCH', `/links/${estado.apagado}`, { activo: false });
  writeFileSync(ESTADO, JSON.stringify(estado));

  await espera(vencido.expiresAt * 1000 + 2000 - Date.now());
  const publico = async (t) => (await api('GET', `/s/${t}`, undefined, false));
  const r50 = await publico(estado.l50);
  const r1 = await publico(estado.l1);
  assert.ok(r50.status === 200 && r50.json.archivos.length === 50, 'link de 50', r50.status);
  assert.ok(r1.status === 200 && r1.json.archivos.length === 1, 'link de 1', r1.status);
  assert.ok((await publico(estado.apagado)).status === 410, 'apagado -> 410');
  assert.ok((await publico(estado.vencido)).status === 410, 'vencido -> 410');
  console.log(`listo: links ${['l50', 'l1', 'apagado', 'vencido'].map((k) => `${k}=${estado[k].slice(0, 6)}`).join(' ')}`);
}

async function limpiar() {
  if (existsSync(ESTADO)) {
    const estado = JSON.parse(readFileSync(ESTADO, 'utf8'));
    for (const t of [estado.l50, estado.l1]) await ok('PATCH', `/links/${t}`, { activo: false });
    console.log('links de 50 y de 1 apagados (el TTL de DynamoDB los borra)');
  }
  const nuestros = (await ok('GET', '/files?carpeta=otros')).filter((a) => a.nombre.startsWith(PREFIJO));
  console.log(`borrando ${nuestros.length} archivos ${PREFIJO}*`);
  for (const a of nuestros) await ok('DELETE', `/files/${a.fileId}`);
  const quedan = (await ok('GET', '/files?carpeta=otros')).filter((a) => a.nombre.startsWith(PREFIJO)).length;
  console.log(`quedan ${quedan}`);
  if (quedan) process.exit(1);
}

const accion = { preparar, limpiar }[process.argv[2]];
if (!accion) {
  console.error('Uso: node tests/load/datos.mjs preparar | limpiar');
  process.exit(1);
}
await accion();
