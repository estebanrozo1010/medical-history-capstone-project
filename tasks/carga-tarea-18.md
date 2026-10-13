# Pruebas de carga — Tarea 18

**Fecha:** 2026-10-13, 11:35–11:48 UTC. Learner Lab, `us-east-1`, API `https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1`.
**Herramienta:** k6 v2.3.0 (zip de las releases de GitHub, sin admin). Scripts en [tests/load/](../tests/load/). Salida de k6: [e1.txt](carga-tarea-18-e1.txt) / [e1.json](carga-tarea-18-e1.json), [e2.txt](carga-tarea-18-e2.txt) / [e2.json](carga-tarea-18-e2.json).
**Datos:** una cuenta de prueba; 50 PDF falsos de 60 bytes (`carga-t18-NN.pdf`, carpeta `otros`) y 4 links, creados por `datos.mjs preparar` y borrados por `datos.mjs limpiar` (531 archivos borrados, 0 restantes; links de 50 y de 1 apagados, el TTL los borra).

## Adaptación a los límites del lab

El stage `v1` tiene throttling de 10 req/s con ráfaga de 20 **para todo el stage**, y Lambda admite 10 ejecuciones simultáneas. Los 50 usuarios y los 200 req/s de [docs/05](../docs/05-seguridad-y-pruebas.md) no caben, así que:

- Lo que se **mide** va a una tasa fija de **8 req/s** (`constant-arrival-rate`), por debajo del límite: tiene que dar 0 de 429.
- El exceso de tasa se prueba aparte con una **ráfaga de 30 req/s durante 20 s**, solo para ver que responde 429 y no 5xx.
- Los usuarios virtuales comparten un ID token: el costo del endpoint no depende de quién lo llama. A 8 req/s y ~150 ms por petición, k6 tuvo de 1 a 5 peticiones en vuelo; Lambda no pasó de 5 ejecuciones simultáneas.

## Escenario 1: `POST /files/upload-url`

8 req/s durante 60 s, hasta 20 usuarios virtuales.

| Peticiones | 201 | 429 | 5xx | min | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| 481 | 481 | 0 | 0 | 105 ms | 140 ms | **280 ms** | 301 ms | 418 ms |

Objetivo p95 < 500 ms y 0 respuestas 5xx: **cumplido**.

## Escenario 2: rutas públicas del link

**Mezcla:** 8 req/s durante 3 min. Links de 50 archivos y de 1 archivo; apagado, vencido (1 min) e inventado alternan entre `GET /s/{token}` y `/files/{fileId}`. El 302 se mide sin seguir la redirección.

| Caso | Peticiones | Código (todas) | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| Listado de 50 archivos (`BatchGetItem`) | 398 | 200 con 50 archivos | 131 ms | **268 ms** | 291 ms | 670 ms |
| Listado de 1 archivo | 241 | 200 con 1 archivo | 122 ms | 258 ms | 268 ms | 374 ms |
| Descarga de un archivo del link de 50 | 377 | 302, firma de 15 s | 123 ms | 263 ms | 320 ms | 431 ms |
| Apagado | 137 | 410 `NO_DISPONIBLE` | 115 ms | 249 ms | 254 ms | 256 ms |
| Vencido | 157 | 410 `NO_DISPONIBLE` | 115 ms | 253 ms | 296 ms | 315 ms |
| Inventado | 130 | 410 `NO_DISPONIBLE` | 116 ms | 253 ms | 258 ms | 271 ms |
| **Total mezcla** | **1 440** | 0 de 429, 0 de 5xx | 126 ms | **264 ms** | 286 ms | 670 ms |

**Ráfaga:** 30 req/s durante 20 s sobre `GET /s/{inventado}`. Resultado: **601 peticiones, 262 de 429** (throttling del stage) y 339 de 410, **0 de 5xx**.

Todos los checks pasaron (3 481 de 3 481). El listado de 50 archivos queda dentro del objetivo.

## Cold start con SnapStart (las 4 Lambdas, 1024 MB)

`node tests/load/cold-start.mjs` lanza 5 peticiones simultáneas por Lambda de la API, para forzar entornos nuevos, y lee de CloudWatch los `REPORT` con `Restore Duration`. Los de `mh-postscan` salen de las 50 subidas del setup.

| Lambda | Restores | Restore | Primera ejecución | Total en Lambda | De punta a punta (cliente) |
|---|---|---|---|---|---|
| `mh-profile` (`GET /profile`) | 5 | 395–639 ms | 235–264 ms | ~0,63–0,90 s | 1,20–1,49 s |
| `mh-files` (`GET /files`) | 4 | 340–525 ms | 135–337 ms | ~0,48–0,86 s | — (*) |
| `mh-links` (`GET /s/{token}`) | 3 | 454–523 ms | 143–782 ms | ~0,60–1,31 s | hasta 833 ms |
| `mh-postscan` (EventBridge) | 3 | 556–702 ms | 502–523 ms | ~1,06–1,22 s | asíncrono |

(*) Los restores de `mh-files` y `mh-links` cayeron en parte durante `datos.mjs preparar`, que corrió justo antes; no se puede separar a qué petición del cliente correspondió cada uno. En caliente, las 3 rutas tardan de 0,18 a 0,61 s de punta a punta.

Todas quedan por debajo del objetivo de 1,5 s de [docs/02](../docs/02-backend.md). `mh-profile` mejoró frente a la Tarea 6 (~1,5 s en Lambda), aunque allí era una sola muestra. No hace falta subir la memoria.

## CloudWatch (11:34–11:49 UTC, toda la sesión con setup y limpieza)

| Métrica | Valor |
|---|---|
| API Gateway `mh-api` / `v1`: `Count` | 3 105 |
| `5XXError` | **0** |
| `4XXError` | 1 037 (262 de 429 en la ráfaga; el resto son 410 esperados) |
| Lambda `Errors` (las 4) | **0** |
| Lambda `Throttles` (las 4 y la cuenta) | **0** |
| Lambda `ConcurrentExecutions` máximo | 5 (en la medición de cold start) |
| Invocaciones | `mh-files` 1 031, `mh-links` 1 800, `mh-postscan` 50, `mh-profile` 10 |

## Costo

- **Antes:** Budgets `mh-lab-50usd` en 0,00 USD reales (pronóstico 0,019 USD); Cost Explorer del mes en 0,00 USD.
- **Después:** sin cambios, porque Budgets se actualizó por última vez a las 09:37 UTC, antes de la prueba (tarda de 8 a 12 h).
- **Estimado:** 3 105 peticiones a API Gateway (0,011 USD); ~600 GB-s de Lambda (0,01 USD); DynamoDB, S3, KMS y logs, centavos. **Menos de 0,05 USD.**

## Hallazgos

- No apareció ningún bug: 0 respuestas 5xx, todos los códigos según [docs/04](../docs/04-modelo-datos-api.md) y sin cambios en el backend.
- La ráfaga deja el stage en 429 para **todos** los usuarios durante 20 s, porque el throttling es global y no por IP. Es el límite de la PoC que ya está en docs/07; en la versión objetivo lo resuelve el WAF `rate-based` por IP.
