# 05 — Seguridad y pruebas

Nombres y rutas según [00](00-plan-general.md) y [04](04-modelo-datos-api.md).

## Checklist de seguridad (revisión final con Opus 5.5)

Las casillas se marcan en la Tarea 17 (pendiente). Los puntos de perímetro, roles por Lambda y GuardDuty son de la versión objetivo; en la PoC se justifican con [07](07-learner-lab.md). Hallazgos del `/agent-skills:ship` (sin Critical ni High) en la Tarea 19 de [tasks/plan.md](../tasks/plan.md).

**Datos**
- [ ] Todos los buckets: Block Public Access, SSE-KMS, TLS obligatorio, versionado en docs.
- [ ] DynamoDB cifrada con la CMK; cédula y fecha de nacimiento cifradas además a nivel de campo (con encryption context).
- [ ] Ningún dato personal ni token en logs (revisar con búsqueda en CloudWatch).
- [ ] Réplica a Glacier verificada con un objeto de prueba.
- [ ] Borrar un archivo elimina todas sus versiones en el bucket de documentos y en el de respaldo.
- [ ] La clave S3 es `{userSub}/{fileId}`: ni la especialidad ni el nombre aparecen en las URLs firmadas.

**Identidad y acceso**
- [ ] Authorizer de Cognito (ID token) en todas las rutas salvo `/api/s/*`.
- [ ] Cada Lambda con su propio rol; sin `Resource: "*"` evitable; sin `AdministratorAccess`.
- [ ] Prueba de aislamiento: usuario A no accede a archivos/links de B (404).
- [ ] La key policy de KMS delega en IAM y no concede `kms:*` a roles de aplicación.

**Archivos y links**
- [ ] Presigned de subida: ≤ 15 min, `Content-Type` y `Content-Length` firmados; `mh-postscan` compara el tamaño real con el declarado.
- [ ] Descarga bloqueada salvo `status = CLEAN`.
- [ ] `expiresAt` validado en código (el TTL de Dynamo es diferido).
- [ ] Token: 32 bytes de `SecureRandom`; apagar el switch corta el acceso de inmediato.
- [ ] Archivo infectado: borrado en ambos buckets y fuera de cualquier link que lo incluya.
- [ ] Un `fileId` que no está en el link no se puede descargar con ese token (404).

**Perímetro y auditoría**
- [ ] WAF en CloudFront (global) y API Gateway (regional) con reglas gestionadas (Common, KnownBadInputs).
- [ ] Regla `rate-based` por IP en el WAF de CloudFront para `/api/s/*`.
- [ ] Headers de seguridad en CloudFront (HSTS, CSP con `connect-src` para Cognito y S3, nosniff, frame-ancestors).
- [ ] CORS limitado al dominio propio (y `localhost:4200` solo en `dev`).
- [ ] CloudTrail activo con eventos de datos del bucket de documentos, en un bucket de logs WORM (Object Lock Compliance; en la PoC, versionado y política que niega borrar).
- [ ] Access logs del stage de API Gateway en CloudWatch (CloudTrail no registra las peticiones a la API).

**Dependencias**
- [ ] `mvn dependency-check` / `npm audit` sin críticos.
- [ ] `cfn-lint` y, opcionalmente, `cfn-guard` sin hallazgos.

## Pruebas

### Backend
- **Unitarias** (`domain`, `application`): JUnit 5 + Mockito con puertos falsos. Casos obligatorios: validación de perfil, reglas de subida (carpeta, tipo, tamaño), `ResolverLink` (activo / apagado / vencido / inexistente / archivos mezclados), propiedad de recursos.
- **Adaptadores**: Testcontainers + LocalStack (DynamoDB, S3, KMS) para repositorios y presigner. En la PoC no se usó: los adaptadores se probaron con el SDK simulado (S3 y `obtenerVarios` de DynamoDB) y con curl contra AWS; `KmsCifrador` y los repositorios de perfil y links no tienen tests propios.
- **Handlers**: eventos de API Gateway de ejemplo contra el handler (`sam local invoke` opcional).
- **Postscan**: eventos de GuardDuty simulados para `NO_THREATS_FOUND`, `THREATS_FOUND`, `FAILED`, tamaño distinto del declarado y reenvío duplicado. En la PoC: eventos `Object Created` simulados (firma correcta → `CLEAN`, otra → `REJECTED`, reenvío, clave ajena y objeto sin registro).

### Frontend
- `ng test` (Vitest en la PoC, 93 tests): `AuthService`, interceptor (añade ID token, refresca ante 401), servicio de subida (partes y progreso; las partes quedan para la Tarea 11b), formularios.
- E2E (Playwright, opcional en la PoC): flujo feliz completo contra el entorno `dev`.

### Infra
- `cfn-lint` en cada template; despliegue y borrado completo en una cuenta de pruebas.
- Prueba post-despliegue (script): crear usuario → subir archivo EICAR (cadena de prueba antivirus estándar) → esperar `INFECTED` → confirmar que no se puede descargar y que no queda ninguna versión en el respaldo. En la PoC el EICAR como `.pdf` queda `REJECTED` y sin versiones (Tarea 9, con curl). Borrado: `infra/teardown.sh`, probado por partes; el borrado y redespliegue completos están pendientes (Tarea 19).

### Carga (fase 7)
- Herramienta: k6 o Artillery.
- Escenario 1: 50 usuarios concurrentes pidiendo presigned URLs (`POST /files/upload-url`); objetivo p95 < 500 ms, 0 errores 5xx.
- Escenario 2: 200 req/s sobre `GET /s/{token}` y `GET /s/{token}/files/{fileId}` (links de 1 y de 50 archivos) mezclando válidos, apagados y vencidos; verificar 200/302/410 correctos y que el límite de tasa responde 429/403 del WAF, no 5xx. Medir la latencia del listado con 50 archivos (`BatchGetItem`).
- Medir cold starts de las 4 Lambdas con SnapStart y ajustar memoria.
- Vigilar el costo durante la prueba (Budgets) y no usar datos reales.

**Resultado en la PoC (Learner Lab, 2026-10-13, k6, informe en [tasks/carga-tarea-18.md](../tasks/carga-tarea-18.md)):** el stage tiene throttling de 10 req/s con ráfaga de 20 y Lambda admite 10 ejecuciones simultáneas. Por eso lo medido va a 8 req/s fijos y el exceso se prueba aparte con una ráfaga de 30 req/s durante 20 s. Scripts en [tests/load/](../tests/load/).
- Escenario 1: 481 `POST /files/upload-url` → todos 201, p50 140 ms, **p95 280 ms**, p99 301 ms, 0 de 5xx y 0 de 429.
- Escenario 2: 1 440 peticiones de mezcla → 200, 302 y 410 correctos en todos los casos, p95 264 ms. **Listado de 50 archivos: p95 268 ms** (p99 291 ms). Ráfaga: 262 de 601 → 429 del throttling y el resto 410, **0 de 5xx**.
- Cold start con SnapStart (1024 MB), restore + primera ejecución: `mh-profile` 0,63–0,90 s (1,2–1,5 s de punta a punta), `mh-files` 0,48–0,86 s, `mh-links` 0,60–1,31 s, `mh-postscan` 1,06–1,22 s. No hace falta subir la memoria.
- CloudWatch: 0 `5XXError` en API Gateway; 0 `Errors` y 0 `Throttles` en las 4 Lambdas; un máximo de 5 ejecuciones simultáneas. Costo estimado menor a 0,05 USD.
- El throttling es global, no por IP: la ráfaga deja la API en 429 para todos durante 20 s. La versión objetivo lo resuelve con el WAF `rate-based` por IP.

## Criterios de aceptación por fase
| Fase | Se acepta cuando |
|---|---|
| 1 | JWT (ID token) obtenido; buckets cifrados y privados |
| 2 | Perfil guardado con campos cifrados; aislamiento entre usuarios probado |
| 3 | EICAR → `INFECTED` y borrado en ambos buckets; PDF limpio → `CLEAN` y descargable; réplica verificada; CloudTrail registra |
| 4 | Link con varios archivos: lista y 302 por archivo si está activo, 410 apagado y vencido; infectado/borrado desaparece del link |
| 5 | Flujo manual completo del frontend OK |
| 6 | App servida por CloudFront+WAF; límite por IP en `/api/s/*` probado |
| 7 | Checklist de este documento completo y pruebas de carga dentro de objetivo |
