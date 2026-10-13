# 04 — Modelo de datos y API

Este documento es el **contrato** entre backend ([02](02-backend.md)), frontend ([03](03-frontend.md)) e infra ([01](01-infra-cloudformation.md)). Cualquier cambio se hace aquí primero.

## Tablas DynamoDB (`PAY_PER_REQUEST`, SSE con CMK)

### `mh-profiles`
| Atributo | Tipo | Nota |
|---|---|---|
| `sub` (PK) | S | `sub` de Cognito |
| `email` | S | del claim `email` del ID token |
| `nombre` | S | |
| `cedulaEnc` | B | cifrada con KMS (encryption context: `sub`) |
| `fechaNacimientoEnc` | B | cifrada con KMS (encryption context: `sub`) |
| `genero` | S | `M` / `F` / `O` |
| `createdAt`, `updatedAt` | N | epoch seconds |

### `mh-files`
| Atributo | Tipo | Nota |
|---|---|---|
| `userSub` (PK) | S | |
| `fileId` (SK) | S | UUID |
| `carpeta` | S | una de las especialidades fijas (ver abajo) |
| `nombre`, `contentType`, `size` | S/S/N | valores declarados al pedir la subida |
| `s3Key` | S | `{userSub}/{fileId}` — sin carpeta ni nombre |
| `status` | S | PoC: `PENDING` → `CLEAN` \| `REJECTED`. Versión objetivo (GuardDuty): `PENDING` → `CLEAN` \| `INFECTED` \| `SCAN_FAILED` |
| `createdAt` | N | |

LSI `byCarpeta`: PK `userSub`, SK `carpeta` (listar los archivos de una carpeta). Los links y `mh-postscan` no necesitan índice: la clave S3 `{userSub}/{fileId}` contiene la clave primaria completa y el link guarda `userSub` + `fileIds`, así que se usa `GetItem`/`BatchGetItem` directo.

Estado efectivo al leer: un registro `PENDING` con más de 1 hora desde `createdAt` se devuelve como `UPLOAD_EXPIRED` (calculado, no se guarda) y no se puede compartir.

Estados que escribe `mh-postscan` (ver [02](02-backend.md)): `CLEAN` (descargable y compartible); `REJECTED` en la PoC, cuando los primeros bytes no corresponden al `contentType` declarado (p. ej. un `.exe` renombrado a `.pdf`): el objeto se borra con todas sus versiones en ambos buckets y el registro queda para que el usuario vea el motivo. `INFECTED` y `SCAN_FAILED` quedan reservados al adaptador de GuardDuty de la versión objetivo. Solo `CLEAN` se puede descargar o compartir.

### `mh-links`
| Atributo | Tipo | Nota |
|---|---|---|
| `token` (PK) | S | 32 bytes aleatorios, base64url |
| `userSub` | S | dueño |
| `fileIds` | SS | 1–50 archivos del mismo dueño, cualquier carpeta |
| `activo` | BOOL | switch manual |
| `expiresAt` | N | epoch seconds, **atributo TTL** |
| `createdAt` | N | |

GSI `byUser`: PK `userSub`, SK `createdAt` (listar links del usuario).

## Carpetas (lista fija)

No hay tabla ni endpoint de carpetas. La lista es una constante compartida por backend y frontend; el backend rechaza (400) cualquier otro valor:

`medicina-general`, `cardiologia`, `dermatologia`, `pediatria`, `ginecologia`, `oftalmologia`, `traumatologia`, `neurologia`, `laboratorio`, `imagenes`, `otros`

El total por carpeta se calcula en el cliente con `GET /files`.

## Endpoints

Base:
- **Arquitectura objetivo:** `https://<dominio>/api` (CloudFront reenvía `/api/*` a API Gateway; ver [01](01-infra-cloudformation.md)).
- **PoC en el Learner Lab (lo implementado):** sin CloudFront no hay prefijo `/api`. La base es la URL del stage, `https://<apiId>.execute-api.us-east-1.amazonaws.com/v1`, y las rutas cuelgan de la raíz: `GET {base}/profile`, `GET {base}/s/{token}`, etc. El front la lee de `apiUrl` en `config.json` (Export `mh-api-ApiUrl`). Ver [07](07-learner-lab.md).

Las rutas de abajo son relativas a la base. Todos requieren `Authorization: Bearer <ID token de Cognito>` salvo los marcados *pública*. Error uniforme: `{ "code": "...", "message": "..." }`.

### Perfil (`mh-profile`)
| Método y ruta | Body | Respuesta |
|---|---|---|
| `POST /profile` | `{nombre, cedula, fechaNacimiento, genero}` | `201` perfil · `409` si ya existe |
| `GET /profile` | — | `200 {email, nombre, cedula, fechaNacimiento, genero}` · `404` |
| `PUT /profile` | igual que POST | `200` perfil · `404` si no existe |

Validación (`400 VALIDACION`): `nombre` 1–100 caracteres; `cedula` de 5 a 15 dígitos; `fechaNacimiento` `AAAA-MM-DD`, desde 1900-01-01 y no futura; `genero` `M`/`F`/`O`. Campos extra del body (incluido `sub` o `email`) se ignoran. Códigos de error: `VALIDACION` (400), `NO_ENCONTRADO` (404), `CONFLICTO` (409), `ERROR_INTERNO` (500).

### Archivos (`mh-files`)
| Método y ruta | Body / query | Respuesta |
|---|---|---|
| `GET /files?carpeta=` | `carpeta` opcional (sin ella devuelve todos) | `200 [{fileId, carpeta, nombre, contentType, size, status, createdAt}]` |
| `POST /files/upload-url` | `{carpeta, nombre, contentType, size}` | `201 {fileId, url, headers, expiresIn}` · `400` carpeta/tipo/tamaño inválido |
| `POST /files/multipart/start` | `{carpeta, nombre, contentType, size}` | `201 {fileId, uploadId, partSize}` (`partSize` = 16 MB) · `400` como `upload-url` |
| `POST /files/multipart/part-url` | `{fileId, uploadId, partNumber}` | `200 {url}` (PUT de 10 min con el `Content-Length` de la parte firmado; S3 devuelve el `ETag`) · `400` si `partNumber` no está entre 1 y `ceil(size/partSize)` |
| `POST /files/multipart/complete` | `{fileId, uploadId, parts:[{partNumber, etag}]}` | `200 {fileId}` · `400` si no vienen las `n` partes en orden o no coinciden con lo subido · `404` si el `uploadId` no es de ese archivo · `409` si el objeto ya existe |
| `POST /files/multipart/abort` | `{fileId, uploadId}` | `204` (también si ya estaba abortada) |
| `GET /files/{fileId}/download-url` | — | `200 {url, expiresIn: 300}` · `409` si no está `CLEAN` · `404` ajeno o inexistente |
| `DELETE /files/{fileId}` | — | `204` en cualquier estado (si está `PENDING`, también aborta sus subidas en partes) (borra todas las versiones, ver [02](02-backend.md)) · `409` si la réplica al respaldo sigue en curso (reintentar) · `404` ajeno o inexistente |

Los cuatro `multipart/*`: `404` si el `fileId` es ajeno o no existe; `409` si el registro ya no está `PENDING`.

Tipos permitidos: `application/pdf`, `image/jpeg`, `image/png`, `video/mp4`. Tamaño máximo en la PoC: 2 GB (video). El nombre original se devuelve al descargar con `response-content-disposition`; no forma parte de la clave S3.

### Links (`mh-links`)
| Método y ruta | Body | Respuesta |
|---|---|---|
| `POST /links` | `{fileIds[1..50], ttlMinutos (1–1440)}` | `201 {token, url, expiresAt}` · `400` si la cantidad, el TTL o el body no son válidos, o hay `fileIds` repetidos · `404` si alguno no es del usuario (se revisa antes que el estado) · `409` si alguno no está `CLEAN` |
| `GET /links` | — | `200 [{token, archivos:[{fileId, nombre}], activo, expiresAt}]`: solo los vigentes (`expiresAt` validado en código), del más nuevo al más viejo; un archivo eliminado ya no aparece en `archivos` |
| `PATCH /links/{token}` | `{activo}` (booleano) | `200 {token, activo, expiresAt}` · `404` si es ajeno, no existe o ya venció (misma respuesta) · `400` si `activo` no es booleano |
| `GET /s/{token}` *(pública)* | — | `200 {expiresAt, archivos:[{fileId, nombre, contentType, size}]}` (solo los `CLEAN` que aún existen) · `410` si no existe, está apagado o venció |
| `GET /s/{token}/files/{fileId}` *(pública)* | — | `302` a presigned GET (1–5 min) · `410` · `404` si el archivo no pertenece al link |

La URL que se comparte es la del frontend, `https://<dominio>/compartido/<token>` (pantalla pública de Angular). En la PoC `mh-links` la arma con su variable `ALLOWED_ORIGIN` (el mismo origen del CORS: `http://localhost:4200` en dev y la URL del sitio de S3 desde la Tarea 16). Esa pantalla llama a `/api/s/{token}`; no hay redirección directa porque un link puede tener varios archivos.

## Reglas transversales
- El `sub` siempre sale del JWT, nunca del body ni de la URL.
- Límites de tasa: throttling de API Gateway por stage (global, no por usuario). El límite por IP en `/api/s/*` (p. ej. 100 peticiones / 5 min) es una regla `rate-based` del WAF de CloudFront.
- CORS: solo el dominio del frontend (más `http://localhost:4200` en `dev`).
