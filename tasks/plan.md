# Plan de implementación: Historial Médico Digital

Especificación: [README.md](../README.md) y [docs/](../docs/00-plan-general.md) (00 a 06). Este plan convierte las fases de `docs/00` en tareas verticales. Lista de verificación: [todo.md](todo.md).

## Resumen

Sistema de historial médico: registro y login (Cognito), perfil cifrado (DynamoDB + KMS), carpetas por especialidad, subida directa a S3 con escaneo de malware (GuardDuty), y links temporales que comparten varios archivos con switch de apagado. Backend: 4 Lambdas Java 21 con SnapStart. Frontend: Angular. Infra: 5 stacks de CloudFormation en `eu-south-2`.

## Decisiones de arquitectura (ya tomadas en los docs)

- Lambdas Java planas + SnapStart, sin Spring Boot ([02](../docs/02-backend.md)).
- ID token de Cognito en todas las llamadas; el `sub` y el `email` salen del JWT.
- `mh-files`: PK `userSub`, SK `fileId`, LSI `byCarpeta`; clave S3 `{userSub}/{fileId}`.
- Links con `fileIds[]`, sin código de confirmación; pantalla pública `/compartido/:token`.
- Carpetas = lista fija de 11 especialidades.
- Contrato único entre front y back: [docs/04](../docs/04-modelo-datos-api.md). No se cambia sin editar ese archivo primero.

## Grafo de dependencias

```
Spike (GuardDuty, Content-Length)         Scaffold repo
        │                                      │
        └──────────────┬───────────────────────┘
                       ▼
        mh-base (KMS, S3, DynamoDB, réplica) ──► mh-auth (Cognito)
                       │                              │
                       └───────────┬──────────────────┘
                                   ▼
                  mh-api + mh-profile  ◄── Angular + auth
                                   │
                  ┌────────────────┼──────────────────┐
                  ▼                ▼                  ▼
           Subida (mh-files)  mh-security +      UI carpetas/subida
                  │           mh-postscan
                  └───────┬────────┘
                          ▼
                 Descarga / borrado / multipart
                          │
                          ▼
              mh-links ──► UI compartir ──► pantalla pública
                          │
                          ▼
                 mh-edge (CloudFront, WAF) ──► endurecimiento ──► carga
```

Se construye de abajo hacia arriba; cada tarea de la columna central entrega una ruta completa (infra + backend + UI cuando aplica).

## Paralelización

- Tras la Tarea 1, el frontend puede avanzar con la API mockeada siguiendo [docs/04](../docs/04-modelo-datos-api.md) (Tareas 5, 8, 14, 15) mientras el backend hace las suyas.
- Secuenciales: `mh-base` → `mh-auth` → `mh-api`; cualquier cambio de esquema DynamoDB (el LSI solo se define al crear la tabla).

## Lista de tareas

### Fase 0: Cimientos y riesgos

#### Tarea 1: Estructura del repo y herramientas
**Descripción:** Crear `backend/` (pom multi-módulo con los 6 módulos vacíos), `frontend/` (Angular), `infra/` (carpeta + `deploy.sh`) y verificar las herramientas. Configurar AWS Budgets.
**Criterios de aceptación:**
- [ ] `mvn -v` (Java 21), `ng version`, `aws --version`, `cfn-lint --version` funcionan
- [ ] `mvn verify` pasa en el pom vacío y `ng build` compila la app base
- [ ] Alerta de AWS Budgets creada
**Verificación:** `mvn verify`; `ng build`; `aws sts get-caller-identity` devuelve la cuenta correcta.
**Dependencias:** Ninguna
**Archivos probables:** `backend/pom.xml`, `backend/*/pom.xml`, `frontend/` (generado), `infra/deploy.sh`, `.gitignore`
**Alcance:** M

#### Tarea 2: Spike de riesgos de AWS (sin código de producto)
**Descripción:** En el Learner Lab, región `us-east-1`, con un stack de CloudFormation mínimo que use `LabRole`, confirmar: (a) **Cognito** (User Pool + App Client) se crea y permite registrar y obtener un ID token; (b) **CloudFront** con S3 y OAC se crea; (c) `AWS::GuardDuty::MalwareProtectionPlan` con `TagScanResult` funciona con `LabRole`; (d) Lambda Java 21 con SnapStart y API Gateway con authorizer funcionan con `LabRole`; (e) un PUT firmado con `Content-Length` rechaza otro tamaño; (f) la replicación de S3 funciona con `LabRole` y filtro por etiqueta (decisión de [docs/01](../docs/01-infra-cloudformation.md)).
**Criterios de aceptación:**
- [ ] Resultado de cada punto (a a f) anotado en `tasks/plan.md` (sección "Resultados del spike")
- [ ] Si (a) falla: decidir el reemplazo del login (p. ej. Lambda de auth + tabla de usuarios + JWT firmado con KMS) y rehacer las Tareas 4 a 7 en el plan antes de seguir
- [ ] Si (b) falla: decidir cómo se sirve el frontend (sitio estático de S3) y ajustar la Tarea 16
- [ ] Docs 00 a 05 actualizados a `us-east-1` y `LabRole`
**Verificación:** prueba manual en una cuenta de pruebas con un bucket temporal; se borra al terminar.
**Dependencias:** Tarea 1
**Archivos probables:** `tasks/plan.md`, `docs/01-*.md`, `docs/02-*.md` si cambia algo
**Alcance:** S

#### Tarea 3: Stack `mh-base`
**Descripción:** KMS (con key policy que delega en IAM), 2 buckets (docs y backup; el del frontend va en la Tarea 16) con cifrado/versionado/lifecycle/CORS, 3 tablas DynamoDB (con LSI y TTL), réplica a Glacier con `LabRole` (probada en el spike). Región `us-east-1`; los roles son `LabRole` por parámetro.
**Criterios de aceptación:**
- [ ] `cfn-lint infra/base.yaml` sin errores
- [ ] Los buckets de documentos y respaldo no son públicos y usan KMS
- [ ] Un objeto subido a `mh-docs` aparece replicado en el bucket de respaldo
**Verificación:** `aws cloudformation deploy`; `aws s3api get-bucket-encryption`; subir un archivo de prueba y listar el respaldo.
**Dependencias:** Tarea 1
**Archivos probables:** `infra/base.yaml`, `infra/deploy.sh`
**Alcance:** M

#### Tarea 4: Stack `mh-auth` y primer ID token
**Descripción:** User Pool, App Client público (SRP) y outputs. Crear un usuario de prueba y obtener un ID token.
**Criterios de aceptación:**
- [ ] `cfn-lint infra/auth.yaml` sin errores
- [ ] Se registra y confirma un usuario, y el ID token contiene `sub` y `email`
**Verificación:** `aws cognito-idp` (sign-up, confirm, initiate-auth); decodificar el token.
**Dependencias:** Tarea 3
**Archivos probables:** `infra/auth.yaml`
**Alcance:** S

### Checkpoint 1: Cimientos
- [ ] Tareas 1 a 4 completas, stacks `mh-base` y `mh-auth` desplegados
- [ ] Spike resuelto y docs ajustados si hizo falta
- [ ] Revisión con una persona antes de seguir

### Fase 1: Registro y perfil (primera ruta completa)

#### Tarea 5: Frontend base con autenticación
**Descripción:** App Angular con `config.json`, `AuthService` (Cognito SRP), registro, confirmación, login, `authGuard` e interceptor con ID token y reintento ante 401.
**Criterios de aceptación:**
- [ ] Registro, confirmación y login funcionan contra el User Pool real
- [ ] Una ruta protegida redirige a `/login` sin sesión
- [ ] El interceptor añade el ID token solo a las llamadas a `apiUrl`
**Verificación:** `ng test --watch=false` (AuthService e interceptor); prueba manual con `ng serve`.
**Dependencias:** Tarea 4
**Archivos probables:** `frontend/src/app/core/auth/*`, `core/config.ts`, `features/auth/*`, `assets/config.json`
**Alcance:** M

#### Tarea 6: `mh-api` y `mh-profile` (backend de perfil)
**Descripción:** Stack `mh-api` mínimo (API Gateway `v1`, authorizer Cognito con ID token, access logs, rutas bajo `/api`) y la Lambda `mh-profile`: `POST/GET/PUT /profile` con cédula y fecha cifradas por KMS (encryption context `sub`).
**Criterios de aceptación:**
- [ ] `POST /api/profile` con ID token guarda el perfil; `GET` lo devuelve descifrado
- [ ] En DynamoDB la cédula y la fecha están cifradas; sin token responde 401
- [ ] Segundo `POST` responde 409; el `sub` nunca se lee del body
- [ ] Cold start con SnapStart medido y anotado
**Verificación:** `mvn -pl domain,application,adapter-in-lambda test`; `cfn-lint infra/api.yaml`; `curl` con ID token.
**Dependencias:** Tareas 3, 4
**Archivos probables:** `infra/api.yaml`, `backend/domain/*`, `backend/application/*`, `backend/adapter-in-lambda/*`, `backend/adapter-out-dynamodb/*`, `backend/adapter-out-kms/*`
**Alcance:** L (dividir en dos al implementar: 6a stack + handler vacío desplegado con SnapStart, 6b lógica de perfil)

**Cold start medido (2026-10-12, `mh-profile`, 1024 MB, jar de 10 MB, una muestra por variante, primer `GET /profile` tras publicar la versión):**

| Variante | Restore | Primera ejecución | Total en Lambda | `curl` de punta a punta |
|---|---|---|---|---|
| SnapStart sin priming | 957 ms | 2 778 ms | ~3,7 s | 4,3 s |
| SnapStart + priming en el init (KMS `Encrypt` y un `GET` de un `sub` inexistente) | 744 ms | 761 ms | ~1,5 s | 2,0 s |
| Peticiones en caliente | — | 50–120 ms | — | 0,2–0,4 s |

El priming va en el constructor de `ProfileHandler` (se ejecuta antes del snapshot) y se repite en cada Lambda nueva. Queda en el límite del objetivo de 1,5 s de [docs/02](../docs/02-backend.md); si hiciera falta bajar más: priming de `Decrypt` o más memoria.

#### Tarea 7: Pantalla de perfil
**Descripción:** Formulario de perfil tras el primer login (`POST /profile`) y pantalla `/perfil` (`GET`/`PUT`).
**Criterios de aceptación:**
- [x] Usuario nuevo es llevado al formulario de perfil si `GET /profile` da 404
- [x] Datos guardados se ven al recargar
**Verificación:** `ng test --watch=false`; flujo manual registro → login → perfil.
**Dependencias:** Tareas 5, 6
**Archivos probables:** `frontend/src/app/features/profile/*`, `core/api/profile.api.ts`
**Alcance:** S

### Checkpoint 2: Primera ruta completa
- [x] Registro → confirmar → login → guardar y ver perfil funciona de punta a punta (2026-10-12, usuario recreado desde cero en la UI)
- [x] Aislamiento: el usuario B no ve datos del usuario A (API en la Tarea 6; en la UI cada usuario ve solo su perfil)
- [x] `mvn verify` (31 tests) y `ng test` (25 tests) pasan
- [x] Revisión con una persona (visto bueno del usuario el 2026-10-12)

### Fase 2: Archivos

#### Tarea 8: Subir y listar archivos (backend + UI)
**Descripción:** `mh-files`: `POST /files/upload-url` (carpeta fija, tipo, tamaño, URL firmada con `Content-Type` y `Content-Length`) y `GET /files`. UI: lista de carpetas, listado por carpeta y subida simple con progreso.
**Criterios de aceptación:**
- [x] Subir un PDF deja un registro `PENDING` con clave `{userSub}/{fileId}` (curl y UI, 2026-10-12; el `userSub` del body se ignora)
- [x] Carpeta, tipo o tamaño inválido responde 400 (curl; también body no JSON y `?carpeta=` inválida)
- [x] `PENDING` de más de 1 hora se muestra como `UPLOAD_EXPIRED` (tests en el borde 3600/3601 s; contra la API real el 2026-10-12: `prueba-8a.pdf`, con su `createdAt` adelantado 2 h en DynamoDB para no esperar la hora, `GET /files` lo devuelve `UPLOAD_EXPIRED` y el registro sigue `PENDING`)
- [x] La UI muestra progreso y el estado (barra de progreso y "Pendiente" en la carpeta; flujo manual con `ng serve`)
**Verificación:** tests de `SolicitarSubida` y `ListarArchivos`; subida real con un PDF de prueba; `ng test --watch=false`.
**Dependencias:** Tarea 6 (UI también 7)
**Archivos probables:** `backend/application/files/*`, `backend/adapter-out-s3/*`, `infra/api.yaml`, `frontend/src/app/features/folders/*`, `features/upload/*`
**Alcance:** L (dividir en 8a backend y 8b UI)

#### Tarea 9: Validación de archivos subidos (`mh-postscan`) y CloudTrail
**Versión PoC (Learner Lab):** GuardDuty no es viable (ver Resultados del spike). El escaneo va detrás de un puerto `ArchivoScanner` en `application`; la PoC usa el adaptador `ValidadorDeTipo` (extensión, tipo MIME declarado y primeros bytes del archivo, p. ej. `%PDF`). **Versión objetivo:** otro adaptador que lee el resultado de GuardDuty ([docs/01](../docs/01-infra-cloudformation.md) y [docs/02](../docs/02-backend.md)); cambiarlo no toca el resto del código.
**Descripción:** Lambda `mh-postscan` disparada por la notificación de S3 al crearse un objeto: lee los primeros bytes, valida contra el tipo declarado y marca `CLEAN` o `REJECTED`; si se rechaza, borra todas las versiones (docs y respaldo). Stack `mh-security` solo con CloudTrail a S3 (sin GuardDuty).
**Criterios de aceptación:**
- [x] PDF real pasa a `CLEAN` (9a, 2026-10-12: PDF válido subido con curl, `CLEAN` en ~3 s)
- [x] Un `.exe` renombrado a `.pdf` pasa a `REJECTED` y no queda ninguna versión en ambos buckets (también un EICAR como `.pdf`; `list-object-versions`: 0 versiones y 0 marcas en `mh-docs` y `mh-docs-backup`. El rechazo tarda ~20 s porque espera a que la réplica termine antes de borrar)
- [x] El estado `INFECTED` queda reservado al adaptador de GuardDuty (versión objetivo) (docs/02 y docs/04)
- [x] Reenviar el mismo evento no falla (idempotente) (`aws lambda invoke` con eventos simulados: `SIN_CAMBIOS`; clave ajena: `IGNORADO`)
- [x] El puerto `ArchivoScanner` tiene dos implementaciones posibles; solo la del validador está activa (`ValidadorDeTipo`; el de GuardDuty solo documentado)
- [x] Archivos `PENDING` de antes de la Tarea 9 revisados una vez con un evento simulado a `mh-postscan:live` (`prueba-8a.pdf` y el PDF de `medicina-general`): ambos `CLEAN`
- [x] Visto bueno del usuario a la Tarea 9 (2026-10-12)
- [x] Extra de la 9a: la URL de subida firma `If-None-Match: *`; reutilizarla para reemplazar un archivo ya escaneado da 412 (probado con curl)
- [x] 9b: `mh-security` con CloudTrail y estado del escaneo en `/subir` (trail `mh-trail` a `mh-trail-<account>` con SSE-S3; registra gestión y datos de `mh-docs`: `PutObject`, `GetObject` por rango, `HeadObject`, `DeleteObject`. `/subir` consulta cada 3 s y muestra Limpio o "subida fallida" con el motivo; flujo manual con `ng serve`: PDF y PNG → Limpio sin recargar, un `.apk` renombrado a `.pdf` de 45 MB → Rechazado y sin versiones en ningún bucket)
**Verificación:** tests de `mh-postscan` con eventos simulados; prueba real con un PDF y un `.exe` renombrado; `aws s3api list-object-versions` en ambos buckets.
**Dependencias:** Tarea 8
**Archivos probables:** `infra/security.yaml`, `infra/api.yaml`, `backend/application/postscan/ArchivoScanner`, `backend/adapter-in-lambda/PostScanHandler`, `backend/application/postscan/ValidadorDeTipo`
**Alcance:** M

#### Tarea 10: Descargar y eliminar archivos
**Descripción:** `GET /files/{id}/download-url` (solo `CLEAN`, con `response-content-disposition`) y `DELETE /files/{id}` (borra todas las versiones). UI: botones descargar y eliminar.
**Criterios de aceptación:**
- [x] Descarga de archivo no `CLEAN` responde 409
- [x] Eliminar borra registro y todas las versiones en ambos buckets
- [x] El usuario B recibe 404 con el `fileId` del usuario A
**Verificación:** tests de autorización; comprobación manual con `list-object-versions`.
**Dependencias:** Tarea 9
**Archivos probables:** `backend/application/files/*`, `backend/adapter-out-s3/*`, `frontend/src/app/features/folders/*`
**Alcance:** M

#### Tarea 11: Subida multipart (archivos grandes)
**Descripción:** Endpoints `multipart/start|part-url|complete|abort` y subida en partes de 16 MB (3 en paralelo) en el front para archivos mayores a 100 MB.
**Criterios de aceptación:**
- [ ] Un video de unos 200 MB se sube y queda `CLEAN` (11a con curl el 2026-10-12: 13 partes de 16 MB, `CLEAN` a los 8 s del `complete` y réplica `COMPLETED` a los 17 s; falta por la UI en 11b)
- [ ] Si falla una parte se puede reintentar; `abort` limpia lo subido (11a con curl: parte con otro tamaño → 403 y reintento OK; `abort` → 204 y `list-multipart-uploads` vacío; falta por la UI en 11b)
- [x] 11a: `uploadId` ajeno a la clave → 404; fileId de otro usuario → 404; registro no `PENDING` → 409; `DELETE` de un `PENDING` aborta sus partes en curso (curl contra la API real)
**Verificación:** prueba manual con un archivo grande; test del servicio de partes.
**Dependencias:** Tarea 10
**Archivos probables:** `backend/adapter-out-s3/Multipart*`, `backend/application/files/*`, `frontend/src/app/features/upload/multipart.service.ts`
**Alcance:** M (dividida en 11a backend y 11b UI)

### Checkpoint 3: Archivos
- [x] Subir, escanear, listar, descargar y eliminar funciona de punta a punta
- [x] EICAR o `.exe` renombrado → `REJECTED`, sin versiones en ningún bucket; réplica verificada; CloudTrail registra (en la PoC `INFECTED` queda reservado al adaptador de GuardDuty; ver Tarea 9)
- [ ] `mvn verify` y `ng test` pasan
- [ ] Revisión con una persona

### Fase 3: Links compartidos

#### Tarea 12: Crear, listar y apagar links (backend)
**Descripción:** `mh-links`: `POST /links` (1 a 50 `fileIds`, TTL 1 a 1440 min), `GET /links`, `PATCH /links/{token}`.
**Criterios de aceptación:**
- [x] Crear con un archivo no `CLEAN` responde 409; con uno ajeno, 404 (curl el 2026-10-12: `REJECTED` y `PENDING` guardado → 409; `CLEAN` de B → 404)
- [x] El token tiene 32 bytes de `SecureRandom` (43 caracteres base64url; decodificado, 32 bytes)
- [x] Solo el dueño puede apagar el link (token de B → 404; dueño → 200 y `activo=false` en DynamoDB)
**Verificación:** `mvn -pl application test`; `curl` con ID token.
**Dependencias:** Tarea 10
**Archivos probables:** `backend/application/links/*`, `backend/adapter-out-dynamodb/*`, `infra/api.yaml`
**Alcance:** M

#### Tarea 13: Resolver link y descargar desde el link (backend público)
**Descripción:** `GET /s/{token}` (lista los archivos `CLEAN` con `BatchGetItem`) y `GET /s/{token}/files/{fileId}` (302 a URL firmada de 1 a 5 min). Rutas sin authorizer, en `mh-links` (en la PoC no hay prefijo `/api`).
**Criterios de aceptación:**
- [x] Link activo y vigente lista sus archivos; apagado, vencido o inexistente responde 410 igual (curl real el 2026-10-12: 200 con 3 archivos; apagado, vencido a los 65 s, inventado y mal formado → el mismo 410 en las dos rutas)
- [x] `expiresAt` se valida en código (no depende del TTL de DynamoDB) (test con reloj fijo: el link sigue en la tabla y responde vacío/410)
- [x] Un `fileId` que no está en el link responde 404 (curl real: de A fuera del link, de B e inventado)
- [x] Archivo rechazado o eliminado desaparece de la lista (en la PoC `REJECTED`; `INFECTED` es de la versión objetivo) (eliminado: curl real, sale de la lista y su descarga da 404; `REJECTED`: solo test, porque en la PoC un `CLEAN` compartido no puede pasar a `REJECTED`)
- [x] Backend desplegado (2026-10-12): `GET /s/{token}` y `GET /s/{token}/files/{fileId}` sin authorizer en `mh-links`, `DOCS_BUCKET` en `api.yaml`. Curl real sin `Authorization`: token inventado, mal formado y de 300 caracteres → el mismo 410 `NO_DISPONIBLE` con `Cache-Control: no-store` y CORS. `mvn verify` (161 tests)
- [x] Extra pedido por el usuario: quien recibe el link elige **ver** (`?modo=ver` → `inline`) o **descargar** (sin parámetro → `attachment`); los botones van en la Tarea 15
**Verificación:** tests de `ResolverLink` y `DescargarDeLink` (activo, apagado, vencido, inexistente, mezclados); prueba con `curl` sin token.
**Prueba real (2026-10-12):** `python tasks/prueba-tarea-13.py` sin fallas; además, 302 de los 3 archivos en modo descargar y ver con el tamaño exacto y firma de 60 s, y 0 eventos con el token completo en `/aws/lambda/mh-links` y `/aws/apigateway/mh-api`. Hallazgo: `fileIds` es un `SS` y DynamoDB no conserva el orden en que se eligieron los archivos; la lista sale en un orden estable pero no el de creación (docs/02 y 04 corregidos). Un `DELETE` justo después de subir da 409 por la réplica (Tarea 10); el script lo reintenta.
**Decisiones de la 13:** métodos `resolver` y `urlDescarga(token, fileId, ver)` en `LinkServicio` (devuelven `Optional`: vacío → 410, sin tocar `ErrorDeDominio` ni los otros handlers); `ArchivoRepositorio.obtenerVarios` con `BatchGetItem` consistente y hasta 4 reintentos de `UnprocessedKeys` (si no, 500); URL firmada de 1 min; archivo eliminado o no `CLEAN` del link → 404 como el que no está en el link; `Cache-Control: no-store` en todas las respuestas de `mh-links`; sin cambios de CORS (el `GET` sin `Authorization` no lleva preflight). `crear`/`listar` de la 12 siguen con su `Query` (`shortcut:`).
**Dependencias:** Tarea 12
**Archivos probables:** `LinkServicio` (o un servicio público aparte) en `application`, método nuevo en `ArchivoRepositorio` y `DynamoArchivoRepositorio`, `LinksHandler`, `infra/api.yaml`
**Pendiente que deja la Tarea 12:**
- Método para leer varios archivos con `BatchGetItem` (`userSub` del link + `fileIds`, hasta 50 claves; reintentar `UnprocessedKeys`) y actualizar los 2 fakes de `ArchivoRepositorio` en los tests. La 12 usa un `Query` de todos los archivos del usuario (comentario `shortcut:` en `LinkServicio`).
- `Auth: {Authorizer: NONE}` en los 2 eventos públicos de `mh-links`; CORS para que la pantalla pública llame a `GET /s/{token}` (la descarga es navegación con 302, no necesita CORS).
- Firmar la descarga desde `mh-links`: `S3Presigner` + `DOCS_BUCKET` en `api.yaml`, reutilizando `AlmacenDeArchivos.urlDescarga` y `ArchivoServicio.disposicion`; respuesta 302 con `Location`. Agregar la firma al priming.
- Log con solo los 6 primeros caracteres del token (como en la 12). El access log usa `$context.resourcePath` (`/s/{token}`), así que no registra el token.
- Datos de prueba: `prueba-12-b.pdf` (`CLEAN`) en la cuenta de B; links de A creados con `POST /links`.
**Alcance:** M

#### Tarea 14: Compartir desde la app
**Descripción:** Selección múltiple en el listado (hasta 50, solo `CLEAN`), elegir duración, crear link, copiar URL; pantalla `/links` con switch y expiración.
**Criterios de aceptación:**
- [x] Se crea un link con 2 archivos y se copia `https://<dominio>/compartido/<token>`
- [x] El switch apaga el link y la lista se actualiza
**Verificación:** `ng test --watch=false`; flujo manual.
**Prueba real (2026-10-12):** `ng serve` contra la API real con un usuario existente: `prueba-9a.pdf` (Laboratorio) + un PDF de Cardiología elegidos en dos carpetas → `POST /links` 201; la URL `http://localhost:4200/compartido/{token}` llegó al portapapeles (Clipboard API en localhost); `GET /s/{token}` sin `Authorization` → 200 con los 2; el link aparece primero en `/links`; switch → `PATCH` 200, aviso `role=status`, `GET /s/{token}` → 410 `NO_DISPONIBLE` y sigue apagado al recargar. Sin errores en consola. `ng test` (79) y `ng build` sin warnings.
**Decisiones de la 14:** selección entre carpetas en un servicio raíz (`Seleccion`, `linkedSignal` sobre `auth.email` para vaciarla al cambiar de usuario, porque `logout` no recarga) con el bloque `app-compartir` en `/carpetas` y `/carpetas/:nombre`; navegar por URL (recargar) pierde la selección. Duraciones de docs/03. `CopiarUrl`: Clipboard API, si no (el sitio HTTP de la Tarea 16 no es contexto seguro) `execCommand('copy')` sobre el texto seleccionado, y si no, aviso para Ctrl+C. Lectura eventual: `LinksApi` recuerda los creados en la sesión y `listar()` los antepone hasta que `GET /links` los traiga. En `/links` la URL se arma con `location.origin` (igual a `ALLOWED_ORIGIN` mientras el front se sirva desde ese origen), porque `GET /links` no devuelve `url`.
**Pendiente para la Tarea 16:** probar Copiar en el sitio HTTP de S3 (solo probado en tests el camino de `execCommand`).
**Dependencias:** Tarea 12 (puede avanzar antes con mock)
**Archivos probables:** `frontend/src/app/features/share/*`, `core/api/links.api.ts`
**Alcance:** M

#### Tarea 15: Pantalla pública `/compartido/:token`
**Descripción:** Vista sin sesión que lista los archivos del link con tamaño y botón de descarga por archivo; mensaje "enlace no disponible" ante 410.
**Criterios de aceptación:**
- [x] Funciona en ventana privada, sin interceptor de token
- [x] Muestra "enlace no disponible" con link apagado o vencido
**Verificación:** `ng test --watch=false`; prueba manual en ventana privada.
**Prueba real (2026-10-13):** `ng serve` en un Chrome sin sesión (sin claves de Cognito en `localStorage`, sin menú): `/compartido/inventado-tarea15` abre sin pasar por `/login`, hace un solo `GET /s/{token}` (sin `OPTIONS`: no lleva `Authorization`) → 410 con `Access-Control-Allow-Origin` y muestra "Enlace no disponible", sin errores en consola. El 410 es idéntico para apagado, vencido e inexistente (Tarea 13). Con sesión, el test con el interceptor real confirma que tampoco se manda `Authorization` (falla si se usa el `HttpClient` normal). `ng test` (89) y `ng build` sin warnings. **Falta:** el caso 200 con un link real (Ver/Descargar de 2 archivos, apagar → 410, vencido de 15 min), que necesita sesión para crear el link; queda en el Checkpoint 4.
**Decisiones de la 15:** `CompartidoApi` usa `new HttpClient(inject(HttpBackend))` para saltar los interceptores (no se toca `authInterceptor`). Ver y Descargar son `<a href>` a `{apiUrl}/s/{token}/files/{fileId}[?modo=ver]` (Ver con `target=_blank rel=noopener`, Descargar en la misma pestaña). Recarga silenciosa en `visibilitychange` (visible) y `pageshow` (vuelta con Atrás desde bfcache); si la lista pasa a 410 o error, el foco va al `h1`, que cambia a "Enlace no disponible". Vencimiento como fecha y hora (`fechaHora`), sin cuenta regresiva. Encabezado global sin menú; sin enlace a login. `tamano` se exporta desde `folders/carpeta.ts`.
**Extra pedido por el usuario (15b, 2026-10-13): permiso del link.** Al compartir se elige "Ver y descargar" (por defecto), "Solo ver" o "Solo descargar"; la pantalla pública muestra solo el botón permitido y `/links` lo indica. El backend lo hace cumplir: `permiso` en `POST /links` (sin él `ambos`, otro valor → 400), guardado en `mh-links` (los links viejos sin el atributo se leen como `ambos`), devuelto en `GET /links` y `GET /s/{token}`, y en `GET /s/{token}/files/{fileId}` fija `inline` (ver) o `attachment` (descargar) por encima de `?modo`. "Solo ver" no impide guardar una copia desde el navegador (lo avisa la UI y docs/04). `mvn verify` (166) y `ng test` (93), `ng build` sin warnings; `mh-api` desplegado (`GET /s/{inventado}` → 410, `GET /links` sin token → 401). **Falta:** probar con un link real de cada permiso (necesita sesión). Después, a pedido del usuario, la firma de `GET /s/{token}/files/{fileId}` bajó de 1 min a **15 s**: tras apagar el link, recargar la pestaña que quedó en la URL de S3 deja de servir a los 15 s (lo ya cargado en el navegador no se puede revocar). Probado por el usuario con `ng serve` (2026-10-13): con el link apagado, la pestaña de Ver deja de cargar al recargar pasados los 15 s.
**Dependencias:** Tarea 13
**Archivos probables:** `frontend/src/app/features/share/public-view/*`
**Lo que deja la Tarea 13:** `GET {apiUrl}/s/{token}` → `200 {expiresAt, archivos:[{fileId, nombre, contentType, size}]}` (puede venir vacía) o `410 {code: NO_DISPONIBLE}`. Por archivo, dos botones que **navegan** (no `HttpClient`, sin CORS): **Ver** → `{apiUrl}/s/{token}/files/{fileId}?modo=ver` (en otra pestaña) y **Descargar** → la misma URL sin `modo`. Un 404 en un archivo (eliminado o rechazado después de cargar la lista) o un 410 (apagado a mitad) los muestra el navegador como JSON: conviene recargar la lista al volver. La URL firmada dura 1 min, así que se pide al hacer clic, nunca se guarda. La lista no viene en el orden en que se eligieron (`SS`): ordenarla en el front (p. ej. por nombre).
**Lo que deja la Tarea 14:**
- Hoy la URL copiada (`/compartido/{token}`) cae en el comodín `{ path: '**', redirectTo: 'perfil' }` de `app.routes.ts`; `/perfil` tiene `authGuard`, así que sin sesión lleva a `/login`. La ruta nueva va **sin guard y antes del comodín**.
- `authInterceptor` le pone el ID token a toda URL que empiece con `apiUrl`: `GET {apiUrl}/s/{token}` debe salir **sin** `Authorization` (si no, lleva preflight de CORS y, sin sesión, el interceptor puede mandar a `/login`).
- `features/share/` ya tiene `Seleccion`, `Compartir`, `CopiarUrl` y `Links`; la pantalla pública va en `features/share/public-view/` y no reutiliza `LinksApi` (esa usa rutas con token).
- Para probar: `/links` muestra los links vigentes con su URL y su switch, incluido el apagado en la prueba de la 14 (vence el 2026-10-13 00:08), útil para el caso 410.
- El menú del encabezado solo aparece con sesión (`auth.email()`), así que en ventana privada no se muestra.

**Prompt para ejecutarla:**
```text
/agent-skills:build Tarea 15 del plan (pantalla pública /compartido/:token)

Lee README.md, tasks/plan.md (Tarea 15 con "Lo que deja la Tarea 13" y "Lo que deja la Tarea 14", Checkpoint 4, "Decisiones de la 13" y "Decisiones de la 14"), tasks/todo.md, docs/03-frontend.md, docs/04-modelo-datos-api.md (GET /s/{token} y GET /s/{token}/files/{fileId}) y docs/07-learner-lab.md. Estamos en el AWS Academy Learner Lab (us-east-1). Las Tareas 1 a 10, la 11a y de la 12 a la 14 están hechas (último commit de la 14: 8478c0c). La 11b (UI multipart) puede estar en curso en otra sesión: no toques frontend/src/app/features/upload. Responde en español latino.

Contexto frontend: Angular con standalone components; AuthService, authInterceptor (agrega el ID token a toda URL que empiece con apiUrl) y authGuard en core/auth; apiUrl en config.json (AppConfig). La ruta comodín '**' redirige a /perfil (con guard), por eso hoy el link compartido lleva al login. features/share ya tiene Seleccion, Compartir, CopiarUrl y /links (Tarea 14).

Contexto backend (ya desplegado, no cambia): GET /s/{token} (pública) → 200 {expiresAt, archivos:[{fileId, nombre, contentType, size}]} solo los CLEAN que existen, sin orden garantizado y puede venir vacía; 410 {code: NO_DISPONIBLE} idéntico para apagado, vencido, inexistente o mal formado. GET /s/{token}/files/{fileId} (pública) → 302 a un GET firmado de 1 min: con ?modo=ver inline, sin él attachment; 404 si el archivo no está en el link, se eliminó o no está CLEAN; 410 como arriba. Cache-Control: no-store. Un GET sin Authorization no lleva preflight de CORS. API base: https://a96z09c5qh.execute-api.us-east-1.amazonaws.com/v1.

Alcance:
- Ruta /compartido/:token sin guard y antes del comodín.
- GET /s/{token} sin Authorization (que el interceptor no lo toque).
- Lista ordenada por nombre con tamaño; por archivo, Ver (en otra pestaña) y Descargar, que navegan a {apiUrl}/s/{token}/files/{fileId}[?modo=ver] (sin HttpClient), con aria-label por archivo.
- 410 → "enlace no disponible"; lista vacía → mensaje propio; red o 500 → role=alert; carga con role=status; accesibilidad como en 10b.
- Recargar la lista al volver a la pestaña (un 404 o 410 de un archivo lo muestra el navegador como JSON).
- Propón antes de hacerlo: (1) cómo saltar el interceptor (HttpContext, excluir rutas /s/ en el interceptor o HttpBackend); (2) qué encabezado ve quien no tiene sesión; (3) Ver y Descargar como <a href> (target=_blank, rel=noopener) o como botones; (4) cuándo recargar la lista (visibilitychange, tras volver de Ver); (5) cómo mostrar expiresAt (fecha y hora o tiempo restante).

Fuera de alcance: backend (salvo un bug real; avísame antes); publicación en S3 y CORS del sitio (Tarea 16).

Verificación: tests primero; ng test --watch=false y ng build sin warnings; flujo manual con ng serve en una ventana privada (sin sesión): crear en la app un link con 2 archivos, abrir su URL en la ventana privada, ver y descargar ambos, comprobar en la pestaña Network que GET /s/{token} no lleva Authorization; apagarlo con el switch en /links, recargar la ventana privada y ver "enlace no disponible"; lo mismo con un link vencido (15 min). Para curl, genera los tokens con infra/id-token.mjs (MH_EMAIL y MH_PASSWORD por variable de entorno) en %TEMP%\mh-token.txt; no escribas la contraseña en ningún archivo.

Haz un commit al terminar y marca la tarea en tasks/todo.md y sus criterios en tasks/plan.md (el Checkpoint 4 queda para mi revisión). Al final dime qué queda para la Tarea 16.

Notas del entorno: en este equipo (Git Bash) los heredocs largos fallan; crea los archivos grandes con la herramienta Write. Mi terminal es PowerShell 5.1: si me das comandos, que sean para PowerShell (sin &&) y sin marcadores tipo <args>.
```
**Alcance:** S

### Checkpoint 4: Links
- [ ] Flujo completo: 2 archivos → un link → abrir en ventana privada → descargar ambos → apagar → 410
- [ ] Un link vencido responde 410
- [ ] Revisión con una persona

### Fase 4: Borde, endurecimiento y entrega

#### Tarea 16: Publicación del frontend y borde
**Versión PoC (Learner Lab):** CloudFront está bloqueado (ver Resultados del spike). El frontend se sirve como sitio estático de S3 público (HTTP) y llama a API Gateway por su URL HTTPS; no hay prefijo `/api`, ni WAF de CloudFront, ni límite por IP (se usa el throttling del stage). **Versión objetivo:** CloudFront + OAC + WAF + dominio, tal como en [docs/01](../docs/01-infra-cloudformation.md); se explica en la sustentación.
**Descripción:** Stack `edge.yaml` con el bucket del sitio web estático (política pública de solo lectura), CORS en API Gateway para el origen del sitio, `config.json` con la URL real de la API y script que sube el build (`ng build` + `aws s3 sync`).
**Criterios de aceptación:**
- [x] La app carga en la URL `http://<bucket>.s3-website-us-east-1.amazonaws.com`
- [x] Recargar una ruta de la SPA (p. ej. `/carpetas`) abre la app y no una página de error (verificar `ErrorDocument`; dejó de verificarse en el spike)
- [x] El navegador llama a la API sin errores de CORS (cabeceras verificadas con `curl`; recorrido en el navegador hecho por el usuario)
- [x] Solo el contenido del frontend es público; los buckets de documentos siguen privados
**Verificación:** `cfn-lint infra/edge.yaml`; abrir la URL y recorrer el flujo; `curl -I` a una ruta SPA.
**Prueba real (2026-10-13):** `mh-edge` y `mh-api` desplegados con `infra/deploy.sh`, front publicado con `node infra/publish-front.mjs`. Sitio `http://mh-front-492183000277.s3-website-us-east-1.amazonaws.com`: `/` → 200; `/carpetas` y `/compartido/abc123` → **404** con el `index.html` (870 B, `text/html`, `no-cache`); `config.json` → 200, `no-cache`, con la URL de API Gateway; `main-*.js` → `text/javascript` y `styles-*.css` → `text/css`, ambos con `public, max-age=31536000, immutable` (el CLI en Windows puso bien el Content-Type). API con `Origin` del sitio: `OPTIONS /links` → 200 con `Access-Control-Allow-Origin` = sitio, `Authorization,Content-Type`; `GET /profile` sin token → 401 y `GET /s/{inventado}` → 410, ambos con el mismo origen. `get-bucket-policy-status`: solo `mh-front` es público (`mh-docs`, `mh-docs-backup`, `mh-artifacts` y `mh-trail`, `false`). `ng serve` (puerto 4201) con el proxy: `/v1/s/{inventado}` → 410 y `/v1/profile` → 401. `ng test` (93) pasa. **Falta (usuario, en el navegador):** login → perfil → carpetas → subir → compartir con cada permiso → `/links` sin errores de CORS; la URL compartida en ventana privada (lista, Ver/Descargar según permiso, apagar → "Enlace no disponible"); Copiar en el sitio HTTP (camino `execCommand`, pendiente de la 14); `ng serve` en 4200 tras reiniciarlo (toma el proxy nuevo). El Chrome que usa Claude mostró una página de error con la URL `http://` (probablemente "Usar siempre conexiones seguras"); con ese ajuste activo hay que aceptar el aviso o desactivarlo para el sitio.
**Probado por el usuario (2026-10-13):** recorrido en el sitio de S3 en el navegador, funcionando.
**Decisiones de la 16:** la API tiene un solo origen, el sitio de S3 (export `mh-edge-SiteUrl`, importado por `mh-api` en el `Cors`, las `GatewayResponses` y `ALLOWED_ORIGIN` de los 3 handlers; se quitó el parámetro `AllowedOrigin`). El desarrollo con `ng serve` no usa CORS: `public/config.json` tiene `apiUrl: "/v1"` y `proxy.conf.json` reenvía `/v1` a API Gateway; `publish-front.mjs` reemplaza `apiUrl` por el export `mh-api-ApiUrl` en el `dist`. Sin cambios en Java. La URL compartida siempre apunta al sitio (también si el link se crea desde `ng serve`). `mh-edge` no se puede borrar mientras `mh-api` importe su export. Política del bucket: solo `s3:GetObject` (sin listado); las rutas SPA responden 404 con el `index.html`, que el navegador muestra igual. Publicación en Node (`infra/publish-front.mjs`, como `id-token.mjs`) para que corra igual en PowerShell y en Git Bash; `deploy.sh` solo despliega `mh-edge`, antes de `mh-api`.
**Dependencias:** Tareas 13, 15
**Archivos probables:** `infra/edge.yaml`, `infra/deploy.sh`, `frontend/public/config.json`
**Alcance:** M

#### Tarea 17: Revisión de seguridad y correcciones
**Descripción:** Pasar la lista de [docs/05](../docs/05-seguridad-y-pruebas.md) con `/agent-skills:security-and-hardening` y `/agent-skills:review`; corregir hallazgos.
**Criterios de aceptación:**
- [ ] Lista de seguridad completa (todas las casillas marcadas o justificadas)
- [ ] `npm audit` y dependencias de Maven sin críticos
**Verificación:** revisión documentada en `tasks/plan.md`; pruebas de aislamiento entre usuarios.
**Dependencias:** Tarea 16
**Archivos probables:** variables según hallazgos
**Alcance:** M

#### Tarea 18: Pruebas de carga
**Descripción:** Escenarios 1 y 2 de [docs/05](../docs/05-seguridad-y-pruebas.md) con k6 o Artillery, y medición de cold starts.
**Criterios de aceptación:**
- [x] p95 menor a 500 ms y 0 errores 5xx en URLs firmadas (481 `upload-url` a 8 req/s: p95 280 ms, 0 de 5xx y 0 de 429)
- [x] Link de 50 archivos responde dentro del objetivo; el límite de tasa responde 4xx, no 5xx (listado de 50: p95 268 ms; ráfaga de 30 req/s → 262 de 429 y 0 de 5xx)
**Verificación:** informe de la herramienta guardado en `tasks/`.
**Prueba real (2026-10-13, 11:35–11:48 UTC):** informe en [carga-tarea-18.md](carga-tarea-18.md), con la salida de k6 en `carga-tarea-18-e1/e2.txt|json`. k6 v2.3.0 (zip, sin admin). Lo medido va a 8 req/s fijos para quedar bajo el throttling del stage (10/20); el exceso se probó con una ráfaga aparte. Escenario 2: 1 440 peticiones con 200, 302 y 410 correctos, p95 264 ms. Cold starts de las 4 Lambdas entre 0,5 y 1,3 s en Lambda (`mh-profile` 1,2–1,5 s de punta a punta). CloudWatch: 0 `5XXError`, 0 `Errors` y 0 `Throttles` de Lambda, máximo 5 ejecuciones simultáneas. Datos: 50 PDF falsos y 4 links de una cuenta de prueba; `datos.mjs limpiar` borró los 531 archivos creados. Costo estimado menor a 0,05 USD; Budgets todavía no lo refleja (se actualiza cada 8 a 12 h). Sin bugs y sin cambios en el backend.
**Decisiones de la 18:** k6 en vez de Artillery (un solo `.exe`, `constant-arrival-rate`, `maxRedirects: 0` para medir el 302 sin bajar de S3). Los usuarios virtuales comparten un token. Setup y limpieza en Node (`tests/load/datos.mjs`, como `id-token.mjs`) a ~4 req/s, reintentando 429 y el 409 de la réplica. Los tokens de los links quedan en `%TEMP%\mh-carga.json`, fuera del repo. La limpieza borra solo `carga-t18-*` de `otros`. Cold start forzado con 5 peticiones simultáneas por Lambda, leyendo `Restore Duration` de los `REPORT`.
**Para repetirla (PowerShell, con el token en `%TEMP%\mh-token.txt` y las credenciales del lab):** `node tests/load/datos.mjs preparar`; `node tests/load/cold-start.mjs 6`; `k6 run --summary-export tasks/carga-tarea-18-e1.json tests/load/escenario1.js`; lo mismo con `escenario2.js`; `node tests/load/datos.mjs limpiar`.
**Dependencias:** Tarea 16
**Archivos probables:** `tests/load/*`
**Alcance:** S

#### Tarea 19: Cierre y entrega
**Descripción:** Actualizar README y docs con lo que cambió, script de borrado de stacks, y pasar `/agent-skills:ship`.
**Criterios de aceptación:**
- [ ] README y docs reflejan lo implementado
- [ ] Un script borra todos los stacks sin dejar buckets ni costos
**Verificación:** despliegue y borrado completos en la cuenta de pruebas.
**Dependencias:** Tareas 17, 18
**Archivos probables:** `README.md`, `docs/*`, `infra/teardown.sh`
**Alcance:** S

### Checkpoint final
- [ ] Todos los criterios de aceptación de las Tareas 1 a 19 cumplidos
- [ ] Flujo completo en el dominio final
- [ ] Listo para sustentación

## Riesgos y mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| Cognito o CloudFront no disponibles en el Learner Lab (no están en su lista de servicios) | Alto | Spike en la Tarea 2, antes de construir sobre ellos; plan B descrito en la tarea |
| Superar los 50 USD pierde la cuenta y todo lo creado | Alto | Budgets (Tarea 1), revisar costo de WAF antes de desplegar, borrar stacks al final de cada sesión |
| `AWS::GuardDuty::MalwareProtectionPlan` no disponible o distinto con `LabRole` | Alto | Spike en la Tarea 2, antes de construir sobre él |
| Cold start de Java con SnapStart peor de lo esperado | Medio | Medirlo ya en la Tarea 6; ajustar memoria o recortar dependencias |
| `Content-Length` firmado no se aplica en la URL de PUT | Medio | Spike (Tarea 2); `mh-postscan` ya compara el tamaño real |
| Cambiar el esquema de DynamoDB después (el LSI no se agrega luego) | Alto | Esquema fijado en [docs/04](../docs/04-modelo-datos-api.md) antes de la Tarea 3 |
| Costos por pruebas y escaneos | Medio | Budgets (Tarea 1), archivos pequeños en pruebas, `teardown.sh` |
| Tareas L en las Tareas 6 y 8 | Medio | Dividirlas en el momento de implementar (6a/6b, 8a/8b) |

## Decisiones del usuario

- Sin dominio propio: la Tarea 16 usa `*.cloudfront.net` (sin Route 53 ni ACM propio).
- Cuenta: **AWS Academy Learner Lab**, presupuesto de 50 USD. Todo se despliega con CloudFormation.
- Verificación de correo: pendiente de explicar y decidir (ver el chat; por defecto, con código).

## Restricciones del Learner Lab (impactan los docs 00 a 05)

Tomadas del texto de instrucciones del laboratorio (actualizado 24/06/2025). Los docs actuales asumen `eu-south-2` y roles propios; **hay que ajustarlos** tras el spike.

| Restricción | Efecto en el diseño |
|---|---|
| Solo `us-east-1` y `us-west-2` | Región pasa a `us-east-1`. ACM/WAF de CloudFront ya quedan en la misma región (un solo template de borde). |
| No se pueden crear roles IAM, solo `LabRole` | Todas las Lambdas, el rol de replicación de S3, el de GuardDuty y el de CloudTrail usan `LabRole` (parámetro `LabRoleArn`). Se pierde el mínimo privilegio por Lambda; documentarlo como límite de la PoC. |
| **Cognito no aparece** en la lista de servicios | Riesgo alto: probar en el spike. Si no funciona, el login se reemplaza (ver Tarea 2). |
| **CloudFront no aparece** en la lista | Riesgo alto: probar en el spike. Si no funciona, el frontend se sirve desde S3 (sitio web estático) y la API se llama directo a API Gateway. |
| Lambda: máx. 10 ejecuciones simultáneas | La prueba de carga (Tarea 18) se reduce; 200 req/s no es posible. |
| CloudTrail sin envío a CloudWatch Logs | Solo logs a S3; ya era así en el diseño. |
| Route 53: no se pueden registrar dominios | Coincide con usar `*.cloudfront.net`. |
| Presupuesto 50 USD; si se supera, se pierde la cuenta | Costos fijos a vigilar: WAF (cada ACL y regla cobra por mes), KMS (1 llave), GuardDuty (por GB escaneado). Revisar con la calculadora de precios de AWS antes de desplegar `mh-edge`; borrar stacks al terminar cada sesión de trabajo. |
| Credenciales temporales por sesión | Copiar las credenciales de "AWS Details" al CLI al iniciar cada sesión, o trabajar desde CloudShell. |

## Resultados del spike (Tarea 2)

Probado el 2026-10-12 en `us-east-1`, Learner Lab, con templates en [infra/spike/](../infra/spike/). Todos los stacks `mh-spike-*` y sus buckets fueron borrados al terminar.

| # | Prueba | Resultado | Detalle |
|---|---|---|---|
| a | Cognito | **Funciona** | User Pool y App Client se crean. Con un usuario de prueba se obtuvo un ID token que trae `email`, `sub` y `token_use=id`. |
| b | CloudFront | **Bloqueado** | Se deniegan `cloudfront:CreateDistribution`, `CreateOriginAccessControl` y `CreateCloudFrontOriginAccessIdentity`. No hay forma de usar CloudFront. |
| b2 | Sitio estático de S3 público | **Funciona (solo HTTP)** | El bucket con política pública se crea y responde. La URL del sitio es `http://`, no `https://`. La regla de SPA (`ErrorDocument` a `index.html`) no se verificó: el `index.html` de prueba no llegó a subirse. |
| c | GuardDuty Malware Protection | **No viable** | El recurso existe, pero con `LabRole` responde que no tiene permisos para validar el bucket; y crear un rol propio falla porque `iam:CreateRole` está denegado. |
| d | Lambda Java 21 + SnapStart + API Gateway + authorizer Cognito | **Funciona** | Sin token: 401. Con ID token: 200. Todo con `LabRole`. No se midió el cold start. |
| e | `Content-Length` firmado en el PUT de S3 | **Funciona** | Declarado 1000, subir 1000: 200. Subir 5000: 403 `SignatureDoesNotMatch`. La comparación de tamaño en `mh-postscan` ya no hace falta. |
| f | Replicación S3 con `LabRole` | **Funciona** | El objeto aparece en el bucket de respaldo con clase `GLACIER` y `ReplicationStatus=COMPLETED` en menos de 1 minuto. El filtro por etiqueta no se probó. |

### Consecuencias para el diseño

- **Sin CloudFront ni WAF de CloudFront:** el frontend se sirve desde S3 como sitio estático (HTTP) y llama a API Gateway por su URL `https://...execute-api...`. Desaparecen el prefijo `/api`, el `OriginPath` y la regla `rate-based` por IP. La Tarea 16 se reescribe.
- **Sin escaneo automático de malware:** no hay GuardDuty. Hay que elegir un reemplazo (ver decisiones abajo). Cambian la Tarea 9, el estado `PENDING → CLEAN` de [docs/04](../docs/04-modelo-datos-api.md) y las reglas de [docs/02](../docs/02-backend.md).
- **Cognito se mantiene** (ID token confirmado): las Tareas 4 a 7 siguen como están.
- **WAF regional:** no se probó; solo se probó que CloudFront esté denegado. Queda por verificar si se necesita.

### Decisiones tomadas tras el spike

La PoC se construye con las restricciones del laboratorio; la **arquitectura objetivo** sigue descrita en README y `docs/00` a `05` para la sustentación. La tabla de diferencias está en [docs/07](../docs/07-learner-lab.md).

1. **Escaneo de malware (Tarea 9):** opción A. Validador de tipo de archivo (extensión, MIME y primeros bytes) detrás de un puerto `ArchivoScanner`; GuardDuty queda como el adaptador de la versión objetivo.
2. **Frontend (Tarea 16):** opción A. Sitio estático de S3 público por HTTP; la API sigue en HTTPS.
