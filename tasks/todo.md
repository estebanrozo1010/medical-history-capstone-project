# Lista de tareas

Detalle, criterios y verificación de cada tarea en [plan.md](plan.md).

## Fase 0: Cimientos y riesgos
- [x] Tarea 1: Estructura del repo y herramientas (M) — alerta de Budgets `mh-lab-50usd` creada (50 USD, avisos a 10, 20, 30 y 40 USD)
- [x] Tarea 2: Spike de riesgos de AWS (S) — resultados en plan.md; CloudFront y GuardDuty bloqueados
- [x] Tarea 3: Stack `mh-base` (M) — desplegado en us-east-1; cifrado KMS, acceso público bloqueado y réplica a GLACIER verificados
- [x] Tarea 4: Stack `mh-auth` y primer ID token (S) — desplegado; registro, confirmación y login SRP verificados, el ID token trae `sub` y `email`

### Checkpoint 1: Cimientos
- [x] Stacks `mh-base` y `mh-auth` desplegados, spike resuelto, revisión humana (visto bueno del usuario el 2026-10-12)

## Fase 1: Registro y perfil
- [x] Tarea 5: Frontend base con autenticación (M)
- [x] Tarea 6: `mh-api` y `mh-profile` (L; dividir en 6a y 6b)
  - [x] 6a: stack `mh-api` (SAM, stage `v1`, authorizer Cognito, CORS, access logs, throttling 10/20) y `mh-profile` vacía con SnapStart; sin token 401, con token llega a la Lambda. Rutas sin prefijo `/api` (docs/04)
  - [x] 6b: POST 201 / segundo POST 409 / GET descifrado / PUT 200 / 400 por validación, probados con curl; cédula y fecha cifradas en DynamoDB con el `sub` como encryption context; cold start ~1,5 s con priming (plan.md); `apiUrl` en `config.json`
  - [x] Aislamiento real con un segundo usuario: B recibe 404 en GET y PUT sin perfil propio, su POST con el `sub` de A en el body crea su propio perfil, y el de A no cambia
- [x] Tarea 7: Pantalla de perfil (S) — un solo componente en `/perfil`: GET 404 → formulario de primer registro (POST); con perfil → editar (PUT). Probado contra la API real con un usuario nuevo y uno existente; los datos persisten al recargar

### Checkpoint 2: Primera ruta completa
- [x] Registro → login → perfil de punta a punta, aislamiento entre usuarios, `mvn verify` y `ng test`, revisión humana (visto bueno del usuario el 2026-10-12)

## Fase 2: Archivos
- [x] Tarea 8: Subir y listar archivos (L; dividir en 8a backend y 8b UI)
  - [x] 8a: `mh-files` en `mh-api` (`POST /files/upload-url` y `GET /files[?carpeta=]`). Probado con curl: 201 con registro `PENDING` y clave `{sub del token}/{fileId}` (el `userSub` del body se ignora); PUT con los headers devueltos 200 y con otro tamaño 403 (`Content-Length` firmado); objeto con `aws:kms`; filtro por carpeta (LSI); carpeta, tipo, tamaño y body inválidos → 400. `UPLOAD_EXPIRED` cubierto por tests (calculado al leer). El CORS del bucket ya permitía PUT desde `localhost:4200`
  - [x] 8b: `FilesApi` y lista fija de carpetas en el front; `/carpetas` (totales con un solo `GET /files`), `/carpetas/:nombre` (tamaño, fecha y estado en español) y `/subir` (validación de tipo y tamaño en el cliente, PUT directo a S3 sin `Authorization` con barra de progreso); menú Carpetas · Subir · Perfil con sesión. `ng test` (41) y `ng build` sin warnings. Flujo manual con `ng serve`: login → subir un PDF real (159 KB) → visto como Pendiente en su carpeta; objeto y registro `PENDING` verificados en AWS
- [x] Tarea 9: Validación de archivos subidos (`mh-postscan`) y CloudTrail (M; dividida en 9a y 9b)
  - [x] 9a: `mh-postscan` (regla EventBridge `Object Created` en `mh-api`; notificación a EventBridge activada en `mh-base`). Puerto `ArchivoScanner` con `ValidadorDeTipo` (12 primeros bytes por `Range`). Probado con curl: PDF → `CLEAN`; `.exe` renombrado y EICAR como `.pdf` → `REJECTED` sin versiones en ningún bucket (espera la réplica antes de borrar); reenviar el evento → `SIN_CAMBIOS`. URL de subida con `If-None-Match: *` (reutilizarla → 412). `mvn verify` (85 tests)
  - [x] 9b: `mh-security` (CloudTrail a S3, SSE-S3, gestión + datos de `mh-docs`; costo de datos < 0,01 USD al ritmo de la PoC) y estado del escaneo en `/subir` (consulta cada 3 s, máx. 2 min, `role=status`). `ng test` (46) y `ng build` sin warnings. Manual: PDF y PNG → Limpio sin recargar; `.apk` renombrado a `.pdf` → Rechazado; el trail entrega a su bucket
- [ ] Tarea 10: Descargar y eliminar archivos (M)
- [ ] Tarea 11: Subida multipart (M)

### Checkpoint 3: Archivos
- [ ] Subir → escanear → descargar → eliminar, EICAR → `INFECTED`, réplica y CloudTrail verificados, revisión humana

## Fase 3: Links compartidos
- [ ] Tarea 12: Crear, listar y apagar links (M)
- [ ] Tarea 13: Resolver link y descargar desde el link (M)
- [ ] Tarea 14: Compartir desde la app (M)
- [ ] Tarea 15: Pantalla pública `/compartido/:token` (S)

### Checkpoint 4: Links
- [ ] 2 archivos en un link, abrir en ventana privada, apagar → 410, vencido → 410, revisión humana

## Fase 4: Borde, endurecimiento y entrega
- [ ] Tarea 16: Publicación del frontend en S3 y borde (M)
- [ ] Tarea 17: Revisión de seguridad y correcciones (M)
- [ ] Tarea 18: Pruebas de carga (S)
- [ ] Tarea 19: Cierre y entrega (S)

### Checkpoint final
- [ ] Criterios de las 19 tareas cumplidos, flujo completo en el dominio final, listo para sustentación
