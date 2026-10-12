# 05 — Seguridad y pruebas

Nombres y rutas según [00](00-plan-general.md) y [04](04-modelo-datos-api.md).

## Checklist de seguridad (revisión final con Opus 5.5)

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
- [ ] CloudTrail activo con eventos de datos del bucket de documentos.
- [ ] Access logs del stage de API Gateway en CloudWatch (CloudTrail no registra las peticiones a la API).

**Dependencias**
- [ ] `mvn dependency-check` / `npm audit` sin críticos.
- [ ] `cfn-lint` y, opcionalmente, `cfn-guard` sin hallazgos.

## Pruebas

### Backend
- **Unitarias** (`domain`, `application`): JUnit 5 + Mockito con puertos falsos. Casos obligatorios: validación de perfil, reglas de subida (carpeta, tipo, tamaño), `ResolverLink` (activo / apagado / vencido / inexistente / archivos mezclados), propiedad de recursos.
- **Adaptadores**: Testcontainers + LocalStack (DynamoDB, S3, KMS) para repositorios y presigner.
- **Handlers**: eventos de API Gateway de ejemplo contra el handler (`sam local invoke` opcional).
- **Postscan**: eventos de GuardDuty simulados para `NO_THREATS_FOUND`, `THREATS_FOUND`, `FAILED`, tamaño distinto del declarado y reenvío duplicado.

### Frontend
- `ng test` (Jasmine/Karma o Jest): `AuthService`, interceptor (añade ID token, refresca ante 401), servicio de subida (partes y progreso), formularios.
- E2E (Playwright, opcional en la PoC): flujo feliz completo contra el entorno `dev`.

### Infra
- `cfn-lint` en cada template; despliegue y borrado completo en una cuenta de pruebas.
- Prueba post-despliegue (script): crear usuario → subir archivo EICAR (cadena de prueba antivirus estándar) → esperar `INFECTED` → confirmar que no se puede descargar y que no queda ninguna versión en el respaldo.

### Carga (fase 7)
- Herramienta: k6 o Artillery.
- Escenario 1: 50 usuarios concurrentes pidiendo presigned URLs (`POST /files/upload-url`); objetivo p95 < 500 ms, 0 errores 5xx.
- Escenario 2: 200 req/s sobre `GET /s/{token}` y `GET /s/{token}/files/{fileId}` (links de 1 y de 50 archivos) mezclando válidos, apagados y vencidos; verificar 200/302/410 correctos y que el límite de tasa responde 429/403 del WAF, no 5xx. Medir la latencia del listado con 50 archivos (`BatchGetItem`).
- Medir cold starts de las 4 Lambdas con SnapStart y ajustar memoria.
- Vigilar el costo durante la prueba (Budgets) y no usar datos reales.

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
