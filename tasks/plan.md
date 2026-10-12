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
- [ ] Revisión con una persona

### Fase 2: Archivos

#### Tarea 8: Subir y listar archivos (backend + UI)
**Descripción:** `mh-files`: `POST /files/upload-url` (carpeta fija, tipo, tamaño, URL firmada con `Content-Type` y `Content-Length`) y `GET /files`. UI: lista de carpetas, listado por carpeta y subida simple con progreso.
**Criterios de aceptación:**
- [ ] Subir un PDF deja un registro `PENDING` con clave `{userSub}/{fileId}`
- [ ] Carpeta, tipo o tamaño inválido responde 400
- [ ] `PENDING` de más de 1 hora se muestra como `UPLOAD_EXPIRED`
- [ ] La UI muestra progreso y el estado
**Verificación:** tests de `SolicitarSubida` y `ListarArchivos`; subida real con un PDF de prueba; `ng test --watch=false`.
**Dependencias:** Tarea 6 (UI también 7)
**Archivos probables:** `backend/application/files/*`, `backend/adapter-out-s3/*`, `infra/api.yaml`, `frontend/src/app/features/folders/*`, `features/upload/*`
**Alcance:** L (dividir en 8a backend y 8b UI)

#### Tarea 9: Validación de archivos subidos (`mh-postscan`) y CloudTrail
**Versión PoC (Learner Lab):** GuardDuty no es viable (ver Resultados del spike). El escaneo va detrás de un puerto `ArchivoScanner` en `application`; la PoC usa el adaptador `ValidadorDeTipo` (extensión, tipo MIME declarado y primeros bytes del archivo, p. ej. `%PDF`). **Versión objetivo:** otro adaptador que lee el resultado de GuardDuty ([docs/01](../docs/01-infra-cloudformation.md) y [docs/02](../docs/02-backend.md)); cambiarlo no toca el resto del código.
**Descripción:** Lambda `mh-postscan` disparada por la notificación de S3 al crearse un objeto: lee los primeros bytes, valida contra el tipo declarado y marca `CLEAN` o `REJECTED`; si se rechaza, borra todas las versiones (docs y respaldo). Stack `mh-security` solo con CloudTrail a S3 (sin GuardDuty).
**Criterios de aceptación:**
- [ ] PDF real pasa a `CLEAN`
- [ ] Un `.exe` renombrado a `.pdf` pasa a `REJECTED` y no queda ninguna versión en ambos buckets
- [ ] El estado `INFECTED` queda reservado al adaptador de GuardDuty (versión objetivo)
- [ ] Reenviar el mismo evento no falla (idempotente)
- [ ] El puerto `ArchivoScanner` tiene dos implementaciones posibles; solo la del validador está activa
**Verificación:** tests de `mh-postscan` con eventos simulados; prueba real con un PDF y un `.exe` renombrado; `aws s3api list-object-versions` en ambos buckets.
**Dependencias:** Tarea 8
**Archivos probables:** `infra/security.yaml`, `infra/api.yaml`, `backend/application/postscan/ArchivoScanner`, `backend/adapter-in-lambda/PostScanHandler`, `backend/application/postscan/ValidadorDeTipo`
**Alcance:** M

#### Tarea 10: Descargar y eliminar archivos
**Descripción:** `GET /files/{id}/download-url` (solo `CLEAN`, con `response-content-disposition`) y `DELETE /files/{id}` (borra todas las versiones). UI: botones descargar y eliminar.
**Criterios de aceptación:**
- [ ] Descarga de archivo no `CLEAN` responde 409
- [ ] Eliminar borra registro y todas las versiones en ambos buckets
- [ ] El usuario B recibe 404 con el `fileId` del usuario A
**Verificación:** tests de autorización; comprobación manual con `list-object-versions`.
**Dependencias:** Tarea 9
**Archivos probables:** `backend/application/files/*`, `backend/adapter-out-s3/*`, `frontend/src/app/features/folders/*`
**Alcance:** M

#### Tarea 11: Subida multipart (archivos grandes)
**Descripción:** Endpoints `multipart/start|part-url|complete|abort` y subida en partes de 16 MB (3 en paralelo) en el front para archivos mayores a 100 MB.
**Criterios de aceptación:**
- [ ] Un video de unos 200 MB se sube y queda `CLEAN`
- [ ] Si falla una parte se puede reintentar; `abort` limpia lo subido
**Verificación:** prueba manual con un archivo grande; test del servicio de partes.
**Dependencias:** Tarea 10
**Archivos probables:** `backend/adapter-out-s3/Multipart*`, `backend/application/files/*`, `frontend/src/app/features/upload/multipart.service.ts`
**Alcance:** M

### Checkpoint 3: Archivos
- [ ] Subir, escanear, listar, descargar y eliminar funciona de punta a punta
- [ ] EICAR → `INFECTED`; réplica verificada; CloudTrail registra
- [x] `mvn verify` (31 tests) y `ng test` (25 tests) pasan
- [ ] Revisión con una persona

### Fase 3: Links compartidos

#### Tarea 12: Crear, listar y apagar links (backend)
**Descripción:** `mh-links`: `POST /links` (1 a 50 `fileIds`, TTL 1 a 1440 min), `GET /links`, `PATCH /links/{token}`.
**Criterios de aceptación:**
- [ ] Crear con un archivo no `CLEAN` responde 409; con uno ajeno, 404
- [ ] El token tiene 32 bytes de `SecureRandom`
- [ ] Solo el dueño puede apagar el link
**Verificación:** `mvn -pl application test`; `curl` con ID token.
**Dependencias:** Tarea 10
**Archivos probables:** `backend/application/links/*`, `backend/adapter-out-dynamodb/*`, `infra/api.yaml`
**Alcance:** M

#### Tarea 13: Resolver link y descargar desde el link (backend público)
**Descripción:** `GET /api/s/{token}` (lista los archivos `CLEAN` con `BatchGetItem`) y `GET /api/s/{token}/files/{fileId}` (302 a URL firmada de 1 a 5 min). Rutas sin authorizer.
**Criterios de aceptación:**
- [ ] Link activo y vigente lista sus archivos; apagado, vencido o inexistente responde 410 igual
- [ ] `expiresAt` se valida en código (no depende del TTL de DynamoDB)
- [ ] Un `fileId` que no está en el link responde 404
- [ ] Archivo infectado o eliminado desaparece de la lista
**Verificación:** tests de `ResolverLink` y `DescargarDeLink` (activo, apagado, vencido, inexistente, mezclados); prueba con `curl` sin token.
**Dependencias:** Tarea 12
**Archivos probables:** `backend/application/links/*`, `backend/adapter-in-lambda/LinksHandler`, `infra/api.yaml`
**Alcance:** M

#### Tarea 14: Compartir desde la app
**Descripción:** Selección múltiple en el listado (hasta 50, solo `CLEAN`), elegir duración, crear link, copiar URL; pantalla `/links` con switch y expiración.
**Criterios de aceptación:**
- [ ] Se crea un link con 2 archivos y se copia `https://<dominio>/compartido/<token>`
- [ ] El switch apaga el link y la lista se actualiza
**Verificación:** `ng test --watch=false`; flujo manual.
**Dependencias:** Tarea 12 (puede avanzar antes con mock)
**Archivos probables:** `frontend/src/app/features/share/*`, `core/api/links.api.ts`
**Alcance:** M

#### Tarea 15: Pantalla pública `/compartido/:token`
**Descripción:** Vista sin sesión que lista los archivos del link con tamaño y botón de descarga por archivo; mensaje "enlace no disponible" ante 410.
**Criterios de aceptación:**
- [ ] Funciona en ventana privada, sin interceptor de token
- [ ] Muestra "enlace no disponible" con link apagado o vencido
**Verificación:** `ng test --watch=false`; prueba manual en ventana privada.
**Dependencias:** Tarea 13
**Archivos probables:** `frontend/src/app/features/share/public-view/*`
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
- [ ] La app carga en la URL `http://<bucket>.s3-website-us-east-1.amazonaws.com`
- [ ] Recargar una ruta de la SPA (p. ej. `/carpetas`) abre la app y no una página de error (verificar `ErrorDocument`; dejó de verificarse en el spike)
- [ ] El navegador llama a la API sin errores de CORS
- [ ] Solo el contenido del frontend es público; los buckets de documentos siguen privados
**Verificación:** `cfn-lint infra/edge.yaml`; abrir la URL y recorrer el flujo; `curl -I` a una ruta SPA.
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
- [ ] p95 menor a 500 ms y 0 errores 5xx en URLs firmadas
- [ ] Link de 50 archivos responde dentro del objetivo; el límite de tasa responde 4xx, no 5xx
**Verificación:** informe de la herramienta guardado en `tasks/`.
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
