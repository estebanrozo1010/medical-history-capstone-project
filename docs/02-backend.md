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
- `SolicitarSubida`: valida carpeta (lista fija de [04](04-modelo-datos-api.md)), tipo MIME (PDF, JPEG/PNG, MP4) y tamaño máximo; crea registro `PENDING` con `fileId` UUID; devuelve presigned PUT (expira 10 min) con `Content-Type`, `Content-Length` e `If-None-Match: *` firmados. Clave S3: `{userSub}/{fileId}`. `If-None-Match` impide reutilizar la URL vigente para reemplazar un archivo ya escaneado (S3 responde 412); si se reutiliza tras un rechazo (el objeto ya se borró), `mh-postscan` lo vuelve a borrar.
  *Límite conocido:* una URL firmada de PUT no fija un tope de tamaño por sí sola. Defensa en dos capas: (1) `Content-Length` firmado (verificar al implementar que S3 rechaza otro valor); (2) `mh-postscan` compara el tamaño real (`HeadObject`) con el declarado y, si no coincide, borra el objeto y marca `SCAN_FAILED`.
- Multipart (archivos > 100 MB): `start` → URL por parte → `complete` (el cliente envía los ETag). `start` valida igual que `SolicitarSubida` y crea el registro `PENDING` antes de `CreateMultipartUpload` (con el `contentType` declarado); partes de 16 MB. Cada URL de `UploadPart` (10 min) firma el `Content-Length` exacto de su parte (16 MB, la última el resto), así la suma no puede pasar del tamaño declarado; `partNumber` de 1 a `ceil(size/16 MB)`. `complete` exige las `n` partes en orden y usa `If-None-Match: *` (no reemplaza un archivo ya escaneado). Las cuatro operaciones exigen el estado **guardado** `PENDING` (no el efectivo: un video grande puede tardar más de 1 h) y si no, 409. El `uploadId` no se guarda: la clave siempre se arma en el servidor como `{sub}/{fileId}` y S3 rechaza (`NoSuchUpload`, 404) un `uploadId` que no es de esa clave. `abort` es idempotente y deja el registro `PENDING` (pasa a `UPLOAD_EXPIRED`, como una subida simple fallida). El lifecycle del bucket aborta multiparts abandonados a los 7 días.
- `UrlDescarga`: solo si `status = CLEAN` (si no, 409); presigned GET de 5 min con `response-content-disposition: attachment; filename="<nombre ASCII>"; filename*=UTF-8''<nombre codificado>` (RFC 6266/5987: el navegador usa `filename*` con tildes y ñ; el respaldo ASCII quita tildes y cambia comillas, `\` y lo demás por `_`).
- `EliminarArchivo`: si el registro está `PENDING`, primero aborta las subidas en partes en curso de la clave (`ListMultipartUploads` con el prefijo; así las partes de un archivo eliminado no quedan 7 días); después borra **todas las versiones** del objeto (`ListObjectVersions` + `DeleteObjectVersion`) en el bucket de documentos y en el de respaldo, y luego el registro; funciona en cualquier estado (sin objeto no falla). Usa la misma espera a la réplica que `mh-postscan`, pero de 8 s (timeout de 15 s y API Gateway corta a 29 s): si la versión sigue `PENDING`, no borra nada y responde 409 "reintenta" (solo pasa al borrar justo después de subir; para un `CLEAN` la réplica ya terminó). Medido con un video de 200 MB en multipart: `CLEAN` a los 8 s y réplica `COMPLETED` a los 17 s del `complete` (dentro de los 40 s de `mh-postscan`). Los links que lo incluían dejan de mostrarlo (se filtra al resolver). Nota: Glacier cobra el mínimo de permanencia aunque se borre antes.
- Autorización: toda operación filtra por el `sub` del JWT; un `fileId` ajeno devuelve 404.

### `mh-links`
- `CrearLink(fileIds, ttlMinutos, permiso)`: `permiso` es `ver`, `descargar` o `ambos` (si no, 400; el handler pone `ambos` si no viene). Además verifica (1 a 50 archivos, sin repetidos: un `SS` de DynamoDB no los admite) que todos son del usuario (si no, 404) y están `CLEAN` (si no, 409); genera `token` aleatorio de 32 bytes (`SecureRandom`, base64url sin relleno, 43 caracteres); guarda `activo=true`, `expiresAt = ahora + ttl` (epoch seconds, máx. 24 h en la PoC). La pertenencia y el estado salen de un `Query` consistente de los archivos del usuario (el mismo de `ListarArchivos`); con miles de archivos convendría un `BatchGetItem` de los `fileIds`. El `SecureRandom` se crea en el init: AWS valida que sigue siendo único tras el restore de SnapStart.
- `CambiarEstado(token, activo)`: el switch manual; solo el dueño. Ajeno, inexistente o vencido → el mismo 404. Un token que no tiene la forma de 43 caracteres base64url ni se busca.
- `ListarLinks`: los del usuario por el GSI `byUser` (lectura eventual), solo los vigentes (`expiresAt` en código), del más nuevo al más viejo. Los nombres salen del mismo `Query` de archivos del usuario, cruzado en memoria; un archivo eliminado se omite de su link.
- La `url` del `POST` es `{ALLOWED_ORIGIN}/compartido/{token}` (ver [04](04-modelo-datos-api.md)). Priming de SnapStart como `mh-files`: `GET /links` y `PATCH` de un token inexistente, sin escribir nada.
- `ResolverLink` (pública, `GET /s/{token}`, `LinkServicio.resolver`): lee el link; si no existe, `activo=false` o `expiresAt <= ahora` → **410** (misma respuesta en los tres casos, para no revelar cuál fue). Un token sin la forma de 43 caracteres base64url da el mismo 410 sin consultar DynamoDB. **Importante:** el TTL de DynamoDB borra con retraso (hasta ~48 h), por eso se valida `expiresAt` en código siempre. Si pasa, devuelve la lista de archivos con un `BatchGetItem` consistente sobre `mh-files` (`userSub` del link + `fileIds`, hasta 50 claves en una petición), en el orden de `fileIds` (un `SS`: DynamoDB no conserva el orden en que se eligieron; el front ordena) y omitiendo los que ya no existen o no están `CLEAN` (así un archivo rechazado o eliminado desaparece del link sin tocarlo). Las `UnprocessedKeys` se reintentan hasta 4 veces (50, 100, 200 y 400 ms); si siguen sin procesarse, 500 en vez de una lista incompleta.
- `DescargarDeLink` (pública, `GET /s/{token}/files/{fileId}`, `LinkServicio.urlDescarga`): repite la validación del link (410), comprueba que `fileId` está en `fileIds` y que sigue existiendo y `CLEAN` (si no, el mismo 404), firma un GET de **15 s** (con el mismo `response-content-disposition` que `UrlDescarga`; con `?modo=ver`, `inline` en vez de `attachment` para verlo en el navegador; quien recibe el link elige) y responde **302**. Bastan 15 s (cortos para que, tras apagar el link, recargar la URL de S3 deje de servir pronto): el navegador sigue el 302 al instante y S3 solo valida la firma al empezar, así que una descarga larga no se corta. La clave S3 es `{userSub del link}/{fileId}`: no se puede pedir un archivo de otro usuario, y el destinatario no ve la especialidad. El `permiso` del link fija la disposición (`ver` → `inline`, `descargar` → `attachment`) por encima de `?modo`; con `ambos` decide `modo`.
- Sin logs del token completo (solo los primeros 6 caracteres): `LINK_RESUELTO`, `DESCARGA_LINK` y `LINK_NO_DISPONIBLE` (este último sirve para ver intentos con tokens inventados). `mh-links` solo firma con `S3Presigner` (variable `DOCS_BUCKET`); no tiene `S3Client`. El priming agrega el `BatchGetItem`, la firma y las dos rutas públicas con un token inexistente.

### `mh-postscan`
**PoC en el Learner Lab (lo implementado, [07](07-learner-lab.md)):**
- Disparada por la regla de EventBridge `Object Created` del bucket de documentos (la notificación a EventBridge se activa en `mh-base`; la regla vive en `mh-api` junto a la Lambda, así no hay dependencia circular entre stacks).
- `PostScanServicio` (application) recibe la clave: si no es `{sub}/{uuid}`, se ignora con un log; si no tiene registro en `mh-files` (el archivo se eliminó y llegó un PUT tardío con la URL aún vigente), borra todas sus versiones (`HUERFANO_BORRADO`), porque el registro siempre se crea antes de firmar la subida; si el registro ya no está `PENDING`, no hace nada (idempotente).
- Escaneo detrás del puerto `ArchivoScanner`. Adaptador activo: `ValidadorDeTipo`, que lee solo los primeros 12 bytes (`GetObject` con `Range`) y compara con la firma del `contentType` declarado (PDF `%PDF`, PNG `89 50 4E 47 0D 0A 1A 0A`, JPEG `FF D8 FF`, MP4 `ftyp` en el offset 4). Coincide → `CLEAN`; no coincide → `REJECTED`. El adaptador de GuardDuty (abajo) es la segunda implementación del puerto; no está construido.
- `REJECTED` → borra todas las versiones en ambos buckets **antes** de marcar el registro (actualización condicional `PENDING → REJECTED`). Carrera con la réplica: antes de borrar espera (hasta 40 s) a que ninguna versión del origen tenga `ReplicationStatus=PENDING`; si se agota, la invocación falla sin borrar y Lambda la reintenta (2 reintentos asíncronos). Así la copia al respaldo no puede llegar después del borrado. Medido: el rechazo tarda unos 20 s por esta espera.
- Un reintento tras un borrado a medias encuentra el objeto ya borrado: `ValidadorDeTipo` lo trata como vacío → `REJECTED` y termina el borrado.

**Versión objetivo (GuardDuty):**
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
