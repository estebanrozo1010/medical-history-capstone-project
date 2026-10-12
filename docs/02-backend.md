# 02 — Backend (Java 21, Lambdas planas + SnapStart)

Decisión: sin Spring Boot. Arquitectura hexagonal con módulos Maven y un handler por Lambda; esto da cold start bajo con SnapStart. Contrato de API en [04](04-modelo-datos-api.md).

## Estructura (`backend/`)

```
backend/
├── pom.xml                      # multi-módulo, Java 21
├── domain/                      # entidades y reglas puras (sin AWS)
├── application/                 # casos de uso + puertos (interfaces)
├── adapter-in-lambda/           # handlers: profile, files, links, postscan
├── adapter-out-dynamodb/        # repositorios (AWS SDK v2 enhanced client)
├── adapter-out-s3/              # presigner, multipart, tags, delete de versiones
└── adapter-out-kms/             # cifrado de campos sensibles
```

Reglas: `domain` y `application` no dependen del SDK de AWS; los adapters implementan los puertos. Cada Lambda se empaqueta como jar sombreado (maven-shade) con solo los módulos que usa. Clientes AWS SDK v2 con `UrlConnectionHttpClient` (más ligero que Apache/Netty) creados como campos estáticos del handler (se capturan en el snapshot).

## Casos de uso por Lambda

### `mh-profile`
- `RegistrarPerfil`: toma `sub` y `email` de los claims del **ID token** (nunca del body; el access token no trae `email`), valida campos (nombre, cédula, fecha de nacimiento, género), cifra cédula y fecha con KMS (`Encrypt` directo, son valores cortos, con encryption context `{sub}`), guarda en `mh-profiles`.
- `ObtenerPerfil`: descifra (mismo encryption context) y devuelve. `ActualizarPerfil`.

### `mh-files`
- `ListarArchivos(carpeta?)`: sin carpeta, `Query` por `userSub`; con carpeta, `Query` sobre el LSI `byCarpeta`. Calcula el estado efectivo (`PENDING` de más de 1 h → `UPLOAD_EXPIRED`).
- `SolicitarSubida`: valida carpeta (lista fija de [04](04-modelo-datos-api.md)), tipo MIME (PDF, JPEG/PNG, MP4) y tamaño máximo; crea registro `PENDING` con `fileId` UUID; devuelve presigned PUT (expira 10 min) con `Content-Type` y `Content-Length` firmados. Clave S3: `{userSub}/{fileId}`.
  *Límite conocido:* una URL firmada de PUT no fija un tope de tamaño por sí sola. Defensa en dos capas: (1) `Content-Length` firmado (verificar al implementar que S3 rechaza otro valor); (2) `mh-postscan` compara el tamaño real (`HeadObject`) con el declarado y, si no coincide, borra el objeto y marca `SCAN_FAILED`.
- Multipart (archivos > 100 MB): `start` → URL por parte → `complete` (el cliente envía los ETag). Se aborta si falla; el lifecycle del bucket aborta multiparts abandonados a los 7 días.
- `UrlDescarga`: solo si `status = CLEAN`; presigned GET de 5 min con `response-content-disposition: attachment; filename=<nombre>`.
- `EliminarArchivo`: borra **todas las versiones** del objeto (`ListObjectVersions` + `DeleteObjectVersion`) en el bucket de documentos y en el de respaldo, y luego el registro. Los links que lo incluían dejan de mostrarlo (se filtra al resolver). Nota: Glacier cobra el mínimo de permanencia aunque se borre antes.
- Autorización: toda operación filtra por el `sub` del JWT; un `fileId` ajeno devuelve 404.

### `mh-links`
- `CrearLink(fileIds, ttlMinutos)`: verifica (1 a 50 archivos) que todos son del usuario y están `CLEAN`; genera `token` aleatorio de 32 bytes (`SecureRandom`, base64url); guarda `activo=true`, `expiresAt = ahora + ttl` (epoch seconds, máx. 24 h en la PoC).
- `CambiarEstado(token, activo)`: el switch manual; solo el dueño.
- `ListarLinks`: los del usuario.
- `ResolverLink` (pública, `GET /s/{token}`): lee el link; si no existe, `activo=false` o `expiresAt < ahora` → **410** (misma respuesta en los tres casos, para no revelar cuál fue). **Importante:** el TTL de DynamoDB borra con retraso (hasta ~48 h), por eso se valida `expiresAt` en código siempre. Si pasa, devuelve la lista de archivos con un `BatchGetItem` sobre `mh-files` (`userSub` del link + `fileIds`), omitiendo los que ya no existen o no están `CLEAN` (así un archivo infectado o eliminado desaparece del link sin tocarlo).
- `DescargarDeLink` (pública, `GET /s/{token}/files/{fileId}`): repite la validación del link, comprueba que `fileId` está en `fileIds` y está `CLEAN`, firma GET de 1–5 min (con `response-content-disposition`) y responde **302**. La clave S3 es solo `{userSub}/{fileId}`: el destinatario no ve la especialidad ni el nombre real.
- Sin logs del token completo (solo los primeros 6 caracteres).

### `mh-postscan`
- Disparada por EventBridge con el resultado de GuardDuty. La clave `{userSub}/{fileId}` da la clave primaria de `mh-files` directamente.
- `NO_THREATS_FOUND` → comprueba el tamaño real contra el declarado; si coincide, `status=CLEAN`; si no, borra y `status=SCAN_FAILED`.
- `THREATS_FOUND` → borra **todas las versiones** del objeto en el bucket de documentos y en el de respaldo (la réplica ya pudo copiarlo), `status=INFECTED` (los links dejan de mostrarlo). No hay carpeta de cuarentena.
- Cualquier otro resultado (`UNSUPPORTED`, `FAILED`) → `status=SCAN_FAILED`, no descargable.
- Idempotente: reintentos del evento no deben fallar.

## SnapStart
- `Runtime: java21`, `SnapStart: ApplyOn: PublishedVersions`, invocar siempre el alias `live`.
- Priming: el constructor del handler (que corre en el init, antes del snapshot) hace una llamada inocua a cada servicio (p. ej. `GET` de un `sub` inexistente, KMS `Encrypt` de un texto fijo). En `mh-profile` bajó la primera ejecución tras el restore de 2,8 s a 0,8 s (medición en `tasks/plan.md`, Tarea 6).
- No guardar en estáticos nada que deba ser único por invocación (tokens, reloj). Regenerar credenciales/aleatoriedad tras restore si se cachea algo (hook `CRaC` solo si hace falta).

## Errores y logs
- Excepciones de dominio → códigos HTTP (400 validación, 403, 404, 409, 410). Cuerpo uniforme `{ "code": "...", "message": "..." }`.
- Log JSON estructurado; nunca registrar cédula, fecha de nacimiento ni tokens completos.

## Criterios de aceptación
- `mvn verify` pasa; cobertura de `domain` y `application` ≥ 80 %.
- Tests de `ResolverLink`: activo, apagado, vencido, inexistente, con archivos mezclados (uno `CLEAN`, uno `INFECTED`, uno eliminado). Tests de `DescargarDeLink`: `fileId` que no pertenece al link devuelve 404.
- Tests de `mh-postscan`: tamaño real distinto del declarado → `SCAN_FAILED`; `THREATS_FOUND` borra versiones en ambos buckets.
- Tests de autorización: usuario A no puede ver/firmar/compartir archivos de B.
- Cold start medido con SnapStart < 1.5 s en `mh-links` (objetivo, medir en fase 7).
