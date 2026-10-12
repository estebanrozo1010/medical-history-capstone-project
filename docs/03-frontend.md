# 03 — Frontend (Angular)

Ruta: `frontend/`. Angular actual (standalone components, signals), SPA estática servida desde S3 + CloudFront. Contrato de API en [04](04-modelo-datos-api.md).

## Estructura

```
frontend/src/app/
├── core/
│   ├── auth/            # AuthService (Cognito), authGuard, auth.interceptor
│   ├── api/             # ProfileApi, FilesApi, LinksApi (HttpClient)
│   └── config.ts        # lee /assets/config.json (apiUrl, userPoolId, clientId)
├── features/
│   ├── auth/            # registro, confirmar código, login
│   ├── folders/         # carpetas por especialidad + listado de archivos
│   ├── upload/          # subida directa a S3 con progreso
│   └── share/           # crear link, lista de links, switch on/off
└── shared/              # componentes UI, pipes
```

La config va en `assets/config.json` (generada en el despliegue desde los Outputs de CFN), no compilada, para usar el mismo build en cualquier entorno.

## Autenticación
- Librería: `amazon-cognito-identity-js` (ligera, flujo SRP). Evitar Amplify completo para no inflar el bundle.
- Flujo de registro: `signUp(email, password)` → confirmar código → login → primera vez: formulario de perfil → `POST /profile`.
- `auth.interceptor`: añade `Authorization: Bearer <idToken>` (el ID token trae `email`; el access token no) a las llamadas a `apiUrl`; refresca el token con el refresh token y reintenta una vez ante 401.
- Tokens en memoria + refresh token en `sessionStorage` (la PoC lo acepta; documentarlo como límite).

## Pantallas / rutas
| Ruta | Guard | Contenido |
|---|---|---|
| `/login`, `/registro`, `/confirmar` | público | Formularios reactivos |
| `/perfil` | auth | Datos del perfil |
| `/carpetas` | auth | Lista fija de especialidades (constante compartida con el backend, ver [04](04-modelo-datos-api.md)); el total por carpeta se calcula con `GET /files` |
| `/carpetas/:nombre` | auth | Archivos, estado (Pendiente / Limpio / Infectado), descargar, eliminar, compartir |
| `/subir` | auth | Selección de carpeta + archivo, barra de progreso |
| `/links` | auth | Links activos con switch, expiración, archivos incluidos y copiar URL |
| `/compartido/:token` | público | Vista de los archivos compartidos y descarga |

## Subida de archivos (`features/upload`)
1. Validar tipo/tamaño en cliente (misma lista que backend; el backend vuelve a validar).
2. `POST /files/upload-url` → `PUT` directo a S3 con `HttpClient` (`reportProgress: true`) y los headers firmados que devuelve la API (`Content-Type`, `Content-Length`); **sin** header `Authorization` en esa llamada.
3. Archivos > 100 MB: flujo multipart (trozos de 16 MB, 3 partes en paralelo, guardar ETag de cada parte).
4. Tras subir, consultar `GET /files?carpeta=` cada 3 s hasta que `status` salga de `PENDING` (máx. 2 min), mostrar resultado del escaneo. Si la API devuelve `UPLOAD_EXPIRED`, `SCAN_FAILED` o `REJECTED` (PoC) / `INFECTED`, mostrar "subida fallida, vuelve a intentar" con el motivo. El resultado va en una región `role=status`; si a los 2 min sigue `PENDING`, se avisa que la revisión tarda (no se da por fallida).

## Compartir (`features/share`)
- Selección múltiple: casillas en el listado de archivos (también "seleccionar toda la carpeta"), hasta 50 archivos, solo los `CLEAN` son seleccionables.
- Elegir duración (15 min, 1 h, 2 h, 24 h) → `POST /links` con `fileIds`.
- Mostrar la URL `https://<dominio>/compartido/<token>` con botón copiar. El switch llama `PATCH /links/{token}`.
- Pantalla pública `/compartido/:token` (sin guard, sin interceptor de token): lista los archivos del link con su tamaño y un botón de descarga por archivo (cada uno abre `/api/s/{token}/files/{fileId}`), y muestra "enlace no disponible" ante un 410.

## Despliegue
```bash
cd frontend && npm ci && ng build --configuration production
aws s3 sync dist/<app>/browser s3://mh-front-<account> --delete
aws cloudfront create-invalidation --distribution-id <ID> --paths "/index.html" "/assets/config.json"
```
Archivos con hash: `Cache-Control: max-age=31536000, immutable`; `index.html` y `config.json`: `no-cache`.

## Accesibilidad y UX
Formularios con etiquetas y mensajes de error asociados, foco visible, contraste AA, estados de carga y error en toda llamada, textos en español.

## Criterios de aceptación
- `ng build` sin warnings de presupuesto; `ng test` pasa.
- Flujo completo manual contra la API real: registro → confirmar → login → perfil → subir 2 archivos → ver `CLEAN` → compartirlos en un solo link → abrir el link en ventana privada y descargar ambos → apagar switch → el link responde 410.
- Sin tokens ni datos personales en `console.log`.
