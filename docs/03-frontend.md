# 03 — Frontend (Angular)

Ruta: `frontend/`. Angular actual (standalone components, signals), SPA estática servida desde el sitio estático de S3 por HTTP en la PoC (S3 + CloudFront en la versión objetivo). Contrato de API en [04](04-modelo-datos-api.md).

## Estructura

```
frontend/src/app/
├── core/
│   ├── auth/            # AuthService (Cognito), authGuard, auth.interceptor
│   ├── api/             # ProfileApi, FilesApi, LinksApi (HttpClient)
│   └── config.ts        # lee /config.json (public/config.json: apiUrl, userPoolId, clientId, region)
├── features/
│   ├── auth/            # registro, confirmar código, login
│   ├── perfil.ts        # /perfil: primer registro (POST) o edición (PUT)
│   ├── folders/         # carpetas por especialidad + listado de archivos
│   ├── upload/          # subida directa a S3 con progreso (en partes: pendiente, Tarea 11b)
│   └── share/           # selección, crear link, lista de links con switch; public-view/: /compartido/:token
```

La config va en `public/config.json` (en el sitio publicado, `apiUrl` sale del export de `mh-api`), no compilada, para usar el mismo build en cualquier entorno.

## Autenticación
- Librería: `amazon-cognito-identity-js` (ligera, flujo SRP). Evitar Amplify completo para no inflar el bundle.
- Flujo de registro: `signUp(email, password)` → confirmar código → login → primera vez: formulario de perfil → `POST /profile`.
- `auth.interceptor`: añade `Authorization: Bearer <idToken>` (el ID token trae `email`; el access token no) a las llamadas a `apiUrl`; ante 401 renueva el token con el refresh token y reintenta una vez; si vuelve a dar 401, cierra la sesión y lleva a `/login`.
- Sesión: el ID token queda en memoria (la librería usa `localStorage` por defecto; aquí, un almacén en memoria) y el refresh token en `sessionStorage`: la sesión sobrevive a recargar, no a cerrar la pestaña. Validez (`infra/auth.yaml`): ID y access token 1 h, refresh token 30 días; el ID token se renueva 30 s antes de vencer. Límites aceptados de la PoC: un XSS podría leer el refresh token, y *Cerrar sesión* (en `/perfil`) solo borra la sesión del navegador: no revoca el refresh token (hallazgo Medium del `/ship`, Tarea 19).
- MFA: el User Pool tiene TOTP opcional, pero la PoC no lo implementa en la interfaz (sin pantalla para activarlo ni para pedir el código al iniciar sesión).

## Pantallas / rutas
| Ruta | Guard | Contenido |
|---|---|---|
| `/login`, `/registro`, `/confirmar` | público | Formularios reactivos |
| `/perfil` | auth | Datos del perfil (POST la primera vez, PUT después) y *Cerrar sesión* |
| `/carpetas` | auth | Lista fija de especialidades (constante compartida con el backend, ver [04](04-modelo-datos-api.md)); el total por carpeta se calcula con `GET /files` |
| `/carpetas/:nombre` | auth | Archivos, estado (Pendiente / Limpio / Rechazado / Subida vencida; Infectado en la versión objetivo), descargar, eliminar, compartir |
| `/subir` | auth | Selección de carpeta (`?carpeta=` la preselecciona) + archivo, barra de progreso y resultado del escaneo |
| `/links` | auth | Links activos con switch, expiración, archivos incluidos y copiar URL |
| `/compartido/:token` | público | Vista de los archivos compartidos y descarga |
| `**` | — | Redirige a `/perfil` (sin sesión, el guard lleva a `/login`) |

Menú (solo con sesión): Carpetas, Subir, Compartidos (`/links`) y Perfil.

## Subida de archivos (`features/upload`)
1. Validar en cliente (misma regla que el backend, que vuelve a validar): PDF, JPG, PNG o MP4 (`File.type`), no vacío y hasta 2 GB.
2. `POST /files/upload-url` → `PUT` directo a S3 con `HttpClient` (`reportProgress: true`) y los headers firmados que devuelve la API (`Content-Type`, `Content-Length`); **sin** header `Authorization` en esa llamada.
3. Archivos > 100 MB: flujo multipart (trozos de 16 MB, 3 partes en paralelo, guardar ETag de cada parte). Backend listo (Tarea 11a); la UI está pendiente (Tarea 11b): mientras tanto, la pantalla sube cualquier archivo de hasta 2 GB con un solo `PUT` firmado.
4. Tras subir, consultar `GET /files?carpeta=` cada 3 s hasta que `status` salga de `PENDING` (máx. 2 min), mostrar resultado del escaneo. Si la API devuelve `UPLOAD_EXPIRED`, `SCAN_FAILED` o `REJECTED` (PoC) / `INFECTED`, mostrar "subida fallida, vuelve a intentar" con el motivo. El resultado va en una región `role=status`; si a los 2 min sigue `PENDING`, se avisa que la revisión tarda (no se da por fallida).

## Compartir (`features/share`)
- Selección múltiple: casillas en el listado de archivos (también "seleccionar toda la carpeta"), hasta 50 archivos, solo los `CLEAN` son seleccionables.
- Elegir duración (15 min, 1 h, 2 h, 24 h; 1 h por defecto) y permiso (Ver y descargar por defecto, Solo ver, Solo descargar) → `POST /links` con `fileIds`, `ttlMinutos` y `permiso`.
- Mostrar la URL que devuelve la API (`{origen del sitio}/compartido/<token>`; en la PoC, `http://mh-front-<account>.s3-website-us-east-1.amazonaws.com/compartido/<token>`) con botón Copiar: Clipboard API y, en el sitio HTTP de la PoC, `execCommand('copy')` o aviso para copiar con Ctrl+C. En `/links` la URL se arma con `location.origin`. El switch llama `PATCH /links/{token}` con `{activo}`; si responde 404, el link se quita de la lista.
- Pantalla pública `/compartido/:token` (sin guard, sin interceptor de token): lista los archivos del link con su tamaño y, según el permiso, los enlaces Ver (otra pestaña, `?modo=ver`) y Descargar por archivo (cada uno navega a `{apiUrl}/s/{token}/files/{fileId}`; `/api/s/...` en la versión objetivo), y muestra "Enlace no disponible" ante un 410. Con la lista vacía muestra un mensaje propio; otros errores, aviso con `role=alert`. Si Ver o Descargar responden 404/410, el navegador muestra el JSON; al volver a la pestaña la pantalla recarga la lista y, si el link ya no sirve, pasa a "Enlace no disponible".

## Despliegue
**PoC (Learner Lab, Tarea 16):** `node infra/publish-front.mjs` (sirve en PowerShell y en Git Bash, con las credenciales del lab en el entorno). Hace `ng build`, escribe en el `dist` un `config.json` con `apiUrl` = export `mh-api-ApiUrl` y sube al bucket del stack `mh-edge` (sitio estático de S3, `http://mh-front-<account>.s3-website-us-east-1.amazonaws.com`) en tres `aws s3 sync`: `.js`/`.css` (con hash) con `Cache-Control: public, max-age=31536000, immutable`; el resto (`index.html`, `config.json`, favicon) con `no-cache`; y al final `--delete`, para no borrar los chunks viejos antes de que el `index.html` nuevo esté arriba. Antes del build actualiza `public/config.json` (`userPoolId`, `clientId`) y el `target` de `proxy.conf.json` con los exports de `mh-auth` y `mh-api`, porque redesplegar desde cero cambia esos ids.

En desarrollo `public/config.json` tiene `"apiUrl": "/v1"` y `ng serve` reenvía `/v1` a API Gateway con `proxy.conf.json`: el navegador ve un solo origen y no hay CORS, porque la API solo permite el origen del sitio. Las subidas (PUT firmado directo a S3) siguen permitidas desde `localhost:4200` por el CORS del bucket de documentos en `dev`.

Rutas de la SPA en el sitio de S3: `ErrorDocument: index.html`, así que `/carpetas` o `/compartido/{token}` abren la app al recargar, pero con estado **404** (el navegador la muestra igual; solo lo notan `curl -I`, buscadores y vistas previas de links). Con CloudFront se reescribe a 200.

**Objetivo (CloudFront):**
```bash
cd frontend && npm ci && ng build --configuration production
aws s3 sync dist/<app>/browser s3://mh-front-<account> --delete
aws cloudfront create-invalidation --distribution-id <ID> --paths "/index.html" "/config.json"
```
Archivos con hash: `Cache-Control: max-age=31536000, immutable`; `index.html` y `config.json`: `no-cache`.

## Accesibilidad y UX
Formularios con etiquetas y mensajes de error asociados, foco visible, contraste AA, estados de carga y error en toda llamada, textos en español.

## Criterios de aceptación
- `ng build` sin warnings de presupuesto; `ng test` pasa.
- Flujo completo manual contra la API real: registro → confirmar → login → perfil → subir 2 archivos → ver `CLEAN` → compartirlos en un solo link → abrir el link en ventana privada y descargar ambos → apagar switch → el link responde 410.
- Sin tokens ni datos personales en `console.log`.
