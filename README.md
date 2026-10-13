# Historial Médico Digital — Capstone Project

Sistema de historial médico donde el usuario puede registrarse, iniciar sesión, organizar sus documentos en carpetas por especialidad, subir archivos (PDF, imágenes, video) y compartirlos mediante un link temporal con control de expiración.

**Stack:**
- Backend: Java 21 en Lambdas con SnapStart (sin Spring Boot), arquitectura hexagonal
- Frontend: Angular
- Infraestructura: AWS con CloudFormation. **Arquitectura objetivo** en `eu-south-2` (Europe/Spain); la **PoC** corre en el AWS Academy Learner Lab, región `us-east-1`

Este README y los docs `00` a `05` describen la arquitectura objetivo. Lo que cambia en la PoC está en la sección 3 y en detalle en [docs/07](docs/07-learner-lab.md).

---

## 1. Arquitectura general

Diagrama completo en `diagramas/historial-medico-arquitectura.drawio` (abrir en [app.diagrams.net](https://app.diagrams.net)) y su vista de referencia en `diagramas/historial-medico-arquitectura.png`.

### Componentes

| Servicio | Rol |
|---|---|
| **Route 53** | Resolución DNS del dominio de la aplicación |
| **CloudFront** | CDN / distribución para el frontend estático y para servir el acceso a archivos compartidos |
| **AWS WAF** | Filtra tráfico malicioso delante de CloudFront y API Gateway |
| **S3 (Frontend)** | Hosting del build estático de Angular |
| **Cognito** | Registro, login y autorización (JWT) de usuarios |
| **API Gateway** | Punto de entrada único de la API |
| **Lambda — Registro/Perfil** | Alta de usuario y gestión de datos de perfil |
| **Lambda — Gestión de Archivos** | Genera presigned URLs de subida/descarga hacia S3 |
| **Lambda — Gestión de Links** | Crea, valida y controla el estado (TTL + switch manual) de los links compartidos |
| **Lambda — Post-escaneo** | Reacciona a los findings de GuardDuty (cuarentena/eliminación de archivos infectados) |
| **DynamoDB** | Perfiles de usuario, registros de archivos y links compartidos (con TTL nativo) |
| **S3 (Documentos)** | Almacenamiento de los archivos médicos, organizados en carpetas por especialidad |
| **GuardDuty (Malware Protection for S3)** | Escaneo automático de malware en cada archivo subido |
| **S3 Glacier** | Backup / recuperación ante desastre (réplica del bucket de documentos) |
| **KMS** | Cifrado en reposo de S3 y DynamoDB, incluidos los datos sensibles del perfil |
| **CloudTrail** | Auditoría de actividad sobre API Gateway y el bucket de documentos |

---

## 2. Decisiones de diseño clave

### 2.1 Registro de usuario
Datos: correo, nombre, cédula/identificación, fecha de nacimiento, género.
- Se guardan en **DynamoDB** (no relacional) usando como partition key el `sub` de Cognito — evita duplicar identidad entre Cognito y la base de perfil.
- Cognito solo gestiona lo mínimo necesario para autenticación (email, `sub`).
- Cédula y fecha de nacimiento se cifran con **KMS** (el `sub` va como encryption context).

### 2.2 Subida de archivos
- El navegador **no** sube el archivo a través de API Gateway (límite de 10MB de payload y 29s de timeout).
- El flujo real: Lambda genera una **presigned URL de S3**, el navegador sube el archivo directo a S3 con esa URL.
- La URL firma `Content-Type`, `Content-Length` (S3 rechaza otro tamaño) e `If-None-Match: *` (no se puede reutilizar para reemplazar un archivo ya escaneado).
- **Multipart upload** para archivos >100MB, obligatorio para >5GB (aplica a los videos). El backend está hecho; la UI en partes está pendiente (Tarea 11b).
- Buenas prácticas aplicadas: expiración corta de la presigned URL (10 min), validación de tipo/tamaño antes de emitirla, registro de cada subida para auditoría.

### 2.3 Links de acceso temporal (expiración + switch manual)
El link que se comparte **no** es una presigned URL directa (esas no se pueden revocar antes de tiempo). En su lugar:
- El link apunta a la pantalla pública del frontend (`/compartido/{token}`), que llama a la Lambda de Gestión de Links con el token (32 bytes aleatorios).
- En DynamoDB se guarda: `token`, `activo` (true/false), `expiresAt` (atributo **TTL** nativo de Dynamo, y además validado en código), el permiso (ver y descargar, solo ver o solo descargar) y la lista de archivos (`fileIds`, hasta 50) que el link comparte.
- En cada acceso, la Lambda valida: ¿activo? ¿no expiró? Si todo pasa, genera una presigned URL de muy corta duración (15 s) solo para esa consulta y redirige.
- El **switch manual** (apagar el link desde la app) actualiza `activo=false` → corte inmediato.
- El **TTL** cubre la expiración automática (15 min a 24 h) aunque el usuario no toque el switch.

Ambos mecanismos son complementarios: uno cubre el olvido, el otro el control manual.

### 2.4 Validación de malware
Se usa **GuardDuty Malware Protection for S3** en vez de una solución propia (ej. ClamAV en Lambda):
- Se activa sin agentes ni infraestructura propia.
- Escanea automáticamente cada objeto subido o modificado.
- Permite etiquetar/poner en cuarentena archivos infectados.
- Pago solo por los datos escaneados.

En la PoC GuardDuty no es viable con `LabRole`: el escaneo va detrás de un puerto `ArchivoScanner` y el adaptador activo valida extensión, tipo MIME y primeros bytes (`CLEAN` o `REJECTED`). Cambiar al adaptador de GuardDuty no toca el resto del backend.

### 2.5 Backup / recuperación ante desastre
El bucket de documentos replica automáticamente (S3 Replication, misma región) hacia un bucket con clase **S3 Glacier**. Es solo para recuperación de emergencia, no para acceso frecuente — por eso se acepta el tiempo de recuperación más lento de Glacier. Borrar un archivo borra todas sus versiones en ambos buckets.

### 2.6 Seguridad y auditoría
- **WAF** delante de CloudFront y API Gateway.
- **KMS** cifra en reposo S3 y DynamoDB.
- **CloudTrail** audita actividad sobre API Gateway y el bucket de documentos; cada petición queda en los access logs del stage (sin tokens ni datos personales).

### 2.7 Región: `eu-south-2` (Europe/Spain)
- Elegida por el lugar de sustentación del proyecto (España).
- Nota técnica: el nombre comercial es "Europe (Spain)"; los datacenters físicos están en **Aragón**, no en la ciudad de Madrid. Madrid sí tiene puntos de presencia de **CloudFront** (edge locations), pero no es donde vive el cómputo/almacenamiento.
- Servicios usados confirmados disponibles en esta región: GuardDuty (desde feb. 2023) y Cognito (desde abr. 2024). CloudFront, Route 53, WAF y KMS son servicios globales sin restricción regional.

---

## 3. PoC en el Learner Lab frente a la versión objetivo

| Tema | Versión objetivo | PoC (Learner Lab) |
|---|---|---|
| Región | `eu-south-2` | `us-east-1` (el lab solo permite `us-east-1` y `us-west-2`) |
| Roles IAM | Un rol por Lambda con mínimo privilegio | `LabRole` para todo (no se pueden crear roles) |
| Frontend | CloudFront + OAC + HTTPS + dominio (Route 53 + ACM) | Sitio estático de S3 público, solo HTTP (CloudFront denegado) |
| API | `/api/*` en el mismo dominio vía CloudFront | URL directa de API Gateway (`https://...execute-api.../v1`) con CORS para el origen del sitio |
| WAF y límite por IP | WAF de CloudFront y regional, regla `rate-based` en `/s/*` | Throttling del stage (10 req/s, ráfaga 20) |
| Malware | GuardDuty Malware Protection | Validador de tipo (extensión, MIME, primeros bytes) |
| CloudTrail | A S3 y CloudWatch Logs | Solo a S3 (SSE-S3) |
| Carga | 200 req/s | 8 req/s medidos (máx. 10 Lambdas simultáneas) |
| Cognito, DynamoDB, KMS, réplica a Glacier, SnapStart | Igual | Igual |

Stacks de la PoC (en orden de despliegue): `mh-base` (KMS, buckets de documentos, respaldo y artefactos, tablas), `mh-auth` (Cognito), `mh-security` (CloudTrail), `mh-edge` (sitio del frontend) y `mh-api` (API Gateway y las 4 Lambdas). `mh-api` importa exports de `mh-base`, `mh-auth` y `mh-edge`.

## 4. Desplegar, publicar, desarrollar y borrar

Requisitos: Java 21, Maven, Node 20+, AWS CLI v2, `cfn-lint` y Git Bash (los `.sh`). Credenciales del lab ("AWS Details") en un archivo fuera de git, por ejemplo `aws-credentials` en la raíz (ya está en `.gitignore`).

En PowerShell:

```powershell
$env:AWS_SHARED_CREDENTIALS_FILE = "$PWD\aws-credentials"
$env:AWS_DEFAULT_REGION = "us-east-1"
& "C:\Program Files\Git\bin\bash.exe" infra/deploy.sh   # cfn-lint, jar de las Lambdas y los 5 stacks en orden
node infra/publish-front.mjs                              # ng build y sync al sitio; imprime la URL
```

- `publish-front.mjs` toma de los exports la URL de la API (`config.json` del sitio) y el User Pool, y actualiza `frontend/public/config.json` y `frontend/proxy.conf.json`: tras redesplegar desde cero (cambian los ids) basta con correrlo.
- Desarrollo: `cd frontend; npx ng serve` en `http://localhost:4200`. El front llama a `/v1` y el proxy lo reenvía a API Gateway, así que no hay CORS.
- Usuario de prueba: se registra desde la app (`/registro`, código por correo). Para probar la API con `curl`: `node infra/id-token.mjs` con `MH_EMAIL` y `MH_PASSWORD` en variables de entorno.
- Pruebas: `mvn -f backend/pom.xml verify` y, en `frontend/`, `npx ng test --watch=false`. Carga: [tasks/carga-tarea-18.md](tasks/carga-tarea-18.md).

**Borrar todo (irreversible):** `& "C:\Program Files\Git\bin\bash.exe" infra/teardown.sh` pide escribir `BORRAR`. Borra los stacks en orden inverso, vaciando antes los buckets (todas las versiones y marcas de borrado, también el respaldo en GLACIER), y al final lista lo que queda. Sobrevive sin costo: la llave KMS en espera de borrado (30 días; no cobra mientras espera), la configuración de cuenta de API Gateway, el historial de stacks borrados y la alerta de Budgets. Glacier cobra igual el mínimo de 90 días de lo que se borre antes (centavos con los datos de prueba). Detalle en [docs/07](docs/07-learner-lab.md).

## 5. Estructura del repo

```
medical-history-capstone-project/
├── backend/      # Maven multi-módulo: domain, application, adapters (lambda, dynamodb, kms, s3)
├── frontend/     # Angular (standalone, signals)
├── infra/        # templates CloudFormation, deploy.sh, teardown.sh, publish-front.mjs, id-token.mjs
├── tests/load/   # k6 y scripts de datos de la prueba de carga
├── docs/         # especificación (00 a 07)
├── tasks/        # plan, lista de tareas e informes de pruebas
├── diagramas/
└── README.md
```

## 6. Plan de desarrollo

Detalle en `docs/` y en [tasks/plan.md](tasks/plan.md):

- [00 Plan general](docs/00-plan-general.md) — fases, requisitos, modelo de Claude por tarea
- [01 Infraestructura CloudFormation](docs/01-infra-cloudformation.md)
- [02 Backend (Java, Lambdas + SnapStart)](docs/02-backend.md)
- [03 Frontend (Angular)](docs/03-frontend.md)
- [04 Modelo de datos y API](docs/04-modelo-datos-api.md)
- [05 Seguridad y pruebas](docs/05-seguridad-y-pruebas.md)
- [06 Skills para ejecutar el plan](docs/06-skills-de-ejecucion.md)
- [07 Restricciones del AWS Academy Learner Lab](docs/07-learner-lab.md)

Nota: el backend se implementa como Lambdas Java planas con SnapStart (sin Spring Boot) para evitar cold starts altos (1,2 a 1,5 s de punta a punta medidos con priming).
