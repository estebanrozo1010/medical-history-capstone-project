// Cold start con SnapStart de las 4 Lambdas (Tarea 18): node tests/load/cold-start.mjs [minutos]
// Lanza 5 peticiones simultáneas por Lambda de la API (fuerza entornos nuevos aunque haya uno caliente), mide de punta a
// punta y lee de CloudWatch los REPORT con "Restore Duration" de los últimos N minutos (15 por defecto); los de
// mh-postscan salen de la subida de `datos.mjs preparar`. Necesita el AWS CLI con credenciales (AWS_SHARED_CREDENTIALS_FILE).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const API = process.env.MH_API ?? 'https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1';
const TOKEN = readFileSync(join(process.env.TEMP, 'mh-token.txt'), 'ascii').trim();
const desde = Date.now() - Number(process.argv[2] ?? 15) * 60_000;
const auth = { Authorization: `Bearer ${TOKEN}` };
const RUTAS = { 'mh-profile': ['/profile', auth], 'mh-files': ['/files?carpeta=pediatria', auth], 'mh-links': [`/s/${'Z'.repeat(43)}`, {}] };

for (const [funcion, [ruta, headers]] of Object.entries(RUTAS)) {
  const tiempos = await Promise.all(Array.from({ length: 5 }, async () => {
    const t0 = performance.now();
    const r = await fetch(API + ruta, { headers });
    await r.arrayBuffer();
    return `${r.status} ${Math.round(performance.now() - t0)} ms`;
  }));
  console.log(`${funcion} ${ruta.slice(0, 24)} (5 a la vez): ${tiempos.join(' | ')}`);
}

await new Promise((r) => setTimeout(r, 15_000)); // los REPORT tardan unos segundos en llegar a CloudWatch
for (const funcion of ['mh-profile', 'mh-files', 'mh-links', 'mh-postscan']) {
  const mensajes = JSON.parse(execFileSync('aws', ['logs', 'filter-log-events', '--log-group-name', `/aws/lambda/${funcion}`,
    '--start-time', String(desde), '--filter-pattern', '"Restore Duration"', '--query', 'events[].message', '--output', 'json'],
  { encoding: 'utf8' }));
  const filas = mensajes.filter((m) => m.startsWith('REPORT ')).map((m) => ({
    restore: Number(m.match(/\tRestore Duration: ([\d.]+) ms/)[1]),
    ejecucion: Number(m.match(/\tDuration: ([\d.]+) ms/)[1]),
  }));
  const rango = (k) => filas.length ? `${Math.min(...filas.map((f) => f[k]))}–${Math.max(...filas.map((f) => f[k]))} ms` : '—';
  console.log(`${funcion}: ${filas.length} restores; restore ${rango('restore')}; primera ejecución ${rango('ejecucion')}`);
}
