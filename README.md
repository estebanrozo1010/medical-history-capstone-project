# Historial Médico Digital — Capstone Project

Sistema de historial médico donde el usuario puede registrarse, iniciar sesión, organizar sus documentos en carpetas por especialidad, subir archivos (PDF, imágenes, video) y compartirlos mediante un link temporal con control de expiración.

**Stack:**
- Backend: Java 21 en Lambdas con SnapStart (sin Spring Boot), arquitectura hexagonal
- Frontend: Angular
- Infraestructura: AWS con CloudFormation. **Arquitectura objetivo** en `eu-south-2` (Europe/Spain); la **PoC** corre en el AWS Academy Learner Lab, región `us-east-1`

Este README y los docs `00` a `05` describen la arquitectura objetivo. Lo que cambia en la PoC está en la sección 3 y en detalle en [docs/07](docs/07-learner-lab.md).

---

## 1. Arquitectura general

Diagramas en `diagramas/` (abrir los `.drawio` en [app.diagrams.net](https://app.diagrams.net) o en draw.io Desktop):

- **Arquitectura objetivo (TO-BE):** `medical-historyv2.drawio` y su vista `medical-historyv2.png`.
- **PoC en el Learner Lab:** `medical-history-poc.drawio` y su vista `medical-history-poc.png`.

Para regenerar un PNG: `& "$env:LOCALAPPDATA\Programs\draw.io\draw.io.exe" -x -f png -s 1.5 -o diagramas/medical-historyv2.png diagramas/medical-historyv2.drawio`.

### Componentes (versión objetivo)

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
- **CloudTrail** audita actividad sobre API Gateway y cada subida, lectura y borrado del bucket de documentos, en un bucket de logs aparte que es **WORM** (S3 Object Lock en modo Compliance): nadie puede borrar esos logs durante su retención. Cada petición a la API queda además en los access logs del stage (sin tokens ni datos personales).

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
| CloudTrail | A S3 WORM (Object Lock, Compliance, 365 días) y CloudWatch Logs | Solo a S3, con versionado y una política que niega a todos borrar logs (*tamper-evident*; el lab niega Object Lock) |
| Carga | 200 req/s | 8 req/s medidos (máx. 10 Lambdas simultáneas) |
| Cognito, DynamoDB, KMS, réplica a Glacier, SnapStart | Igual | Igual |

Stacks de la PoC (en orden de despliegue): `mh-base` (KMS, buckets de documentos, respaldo y artefactos, tablas), `mh-auth` (Cognito), `mh-security` (CloudTrail), `mh-edge` (sitio del frontend) y `mh-api` (API Gateway y las 4 Lambdas). `mh-api` importa exports de `mh-base`, `mh-auth` y `mh-edge`.

## 4. Instalación de la PoC paso a paso

Todo se hace desde **PowerShell** en Windows; los dos scripts `.sh` corren con el Bash de Git for Windows. En CloudShell o Linux los mismos pasos funcionan con `bash infra/...` y `export` en lugar de `$env:`.

### 4.1 Herramientas

| Herramienta | Versión usada | Para qué | Instalación en Windows |
|---|---|---|---|
| Java 21 | Amazon Corretto 21 | Compilar las Lambdas | `winget install Amazon.Corretto.21.JDK` |
| Maven | 3.9 | Build del backend | Zip de [maven.apache.org](https://maven.apache.org/download.cgi) y su `bin` en el `PATH` |
| Node.js | 20 o superior (probado con 25) | Angular y los scripts `.mjs` | `winget install OpenJS.NodeJS.LTS` |
| AWS CLI v2 | 2.37 | Despliegue y comprobaciones | `winget install Amazon.AWSCLI` |
| cfn-lint | 1.57 | Validar los templates (lo corre `deploy.sh`) | `pip install cfn-lint` |
| Git for Windows | — | Git y Bash para los `.sh` | `winget install Git.Git` |
| k6 (opcional) | 2.3 | Pruebas de carga | `winget install GrafanaLabs.k6` o el zip de [k6.io](https://k6.io) |

Angular CLI no hace falta global: se usa con `npx ng` desde `frontend/`. Comprobar con `java -version`, `mvn -v`, `node -v`, `aws --version` y `cfn-lint --version`.

### 4.2 Credenciales del Learner Lab

1. En AWS Academy: *Start Lab* y esperar el punto verde.
2. *AWS Details* → *Show* en "AWS CLI" y copiar el bloque `[default]` (`aws_access_key_id`, `aws_secret_access_key` y `aws_session_token`) a un archivo `aws-credentials` en la raíz del repo. Está en `.gitignore`: **nunca** se sube.
3. En cada terminal nueva:

```powershell
$env:AWS_SHARED_CREDENTIALS_FILE = "$PWD\aws-credentials"
$env:AWS_DEFAULT_REGION = "us-east-1"
aws sts get-caller-identity
```

Las credenciales vencen con la sesión del lab: si un comando responde `ExpiredToken`, se copian otra vez. Presupuesto del lab: 50 USD; si se supera se pierde la cuenta (ver [docs/07](docs/07-learner-lab.md)). Conviene una alerta de AWS Budgets (la de este proyecto, `mh-lab-50usd`, se creó a mano).

### 4.3 Desplegar el backend y la infraestructura

```powershell
cd frontend; npm ci; cd ..
& "C:\Program Files\Git\bin\bash.exe" infra/deploy.sh
```

`deploy.sh` corre `cfn-lint`, compila el jar de las Lambdas (`mvn package`), lo sube al bucket de artefactos y despliega en orden `mh-base` → `mh-auth` → `mh-security` → `mh-edge` → `mh-api`. Se puede volver a correr: sin cambios no hace nada.

### 4.4 Publicar el frontend

```powershell
node infra/publish-front.mjs
```

Hace `ng build`, escribe en el `config.json` del sitio la URL de la API y el User Pool (de los exports de los stacks) y sube el build al bucket del sitio. Al final imprime la URL: `http://mh-front-<cuenta>.s3-website-us-east-1.amazonaws.com`. También actualiza `frontend/public/config.json` y `frontend/proxy.conf.json` para `ng serve`; tras un despliegue desde cero esos dos archivos quedan con cambios en git (son ids públicos, se pueden commitear).

El sitio es HTTP (sin CloudFront en el lab). Si Chrome tiene activo "Usar siempre conexiones seguras", muestra un aviso que hay que aceptar.

### 4.5 Usuario de prueba y recorrido básico

1. Abrir la URL del sitio → *Registro* con un correo real (llega un código) → *Confirmar* → *Login*.
2. El primer login lleva al formulario de perfil (la cédula y la fecha se guardan cifradas).
3. *Subir* un PDF o una imagen → en su carpeta pasa de Pendiente a Limpio en unos segundos (un `.exe` renombrado a `.pdf` queda Rechazado y se borra).
4. En la carpeta, marcar archivos limpios → *Compartir* (duración y permiso) → copiar la URL y abrirla en una ventana privada → Ver o Descargar.
5. *Compartidos* → apagar el switch → la ventana privada muestra "Enlace no disponible" al recargar.

Los usuarios viven en Cognito: se pierden al borrar los stacks.

### 4.6 Desarrollo local y pruebas

```powershell
cd frontend; npx ng serve          # http://localhost:4200, contra la API real
npx ng test --watch=false          # pruebas del frontend (Vitest)
cd ..; mvn -f backend/pom.xml verify   # pruebas del backend (JUnit 5)
```

`ng serve` usa `/v1` y el proxy de `proxy.conf.json` lo reenvía a API Gateway, así que no hay CORS. Hay que usar el puerto 4200: es el único origen local que acepta el bucket de documentos para las subidas.

Para probar la API con `curl`, el ID token sale de `node infra/id-token.mjs` con el correo y la contraseña en variables de entorno (no se escriben en ningún archivo):

```powershell
$env:MH_EMAIL = "correo@ejemplo.com"
$clave = Read-Host "Contraseña" -AsSecureString
$env:MH_PASSWORD = [Net.NetworkCredential]::new("", $clave).Password
node infra/id-token.mjs | Out-File -Encoding ascii "$env:TEMP\mh-token.txt"
```

Pruebas de carga (opcional): pasos e informe en [tasks/carga-tarea-18.md](tasks/carga-tarea-18.md) y scripts en `tests/load/`.

### 4.7 Borrar todo

```powershell
& "C:\Program Files\Git\bin\bash.exe" infra/teardown.sh
```

**Irreversible.** Pide escribir `BORRAR` y se niega si la región no es `us-east-1`. Para vaciar el bucket de CloudTrail primero quita su política (la que niega borrar logs); ese cambio queda en CloudTrail. Borra los stacks en orden inverso (`mh-api` → `mh-edge` → `mh-security` → `mh-auth` → `mh-base`), vaciando antes los buckets (todas las versiones y marcas de borrado, también el respaldo en GLACIER), y al final lista lo que queda. Sobrevive sin costo: la llave KMS en espera de borrado (30 días; no cobra mientras espera), la configuración de cuenta de API Gateway, el historial de stacks borrados y la alerta de Budgets. Glacier cobra el mínimo de 90 días de lo que se borre antes (centavos con los datos de prueba). Para volver a levantar todo: pasos 4.3 a 4.5. Detalle en [docs/07](docs/07-learner-lab.md).

### 4.8 Cómo se construyó con Claude Code

El proyecto se hizo tarea por tarea con los skills de `agent-skills` (detalle en [docs/06](docs/06-skills-de-ejecucion.md)):

| Momento | Skill |
|---|---|
| Plan de tareas con criterios de aceptación | `/agent-skills:plan` → [tasks/plan.md](tasks/plan.md) y [tasks/todo.md](tasks/todo.md) |
| Cada tarea (prueba que falla, código, verificación, commit) | `/agent-skills:build Tarea N del plan (...)`; el prompt de ejemplo está en la Tarea 15 del plan |
| Reglas con TDD (links, postscan, autorización) | `/agent-skills:test` |
| Pantallas de Angular | `frontend-design:frontend-design` |
| Revisión de código y de seguridad | `/agent-skills:review` y `/agent-skills:security-and-hardening` (Tarea 17, pendiente) |
| Cierre: go/no-go | `/agent-skills:ship` (resultado en la Tarea 19 del plan) |

## 5. Estructura del repo

```
medical-history-capstone-project/
├── backend/      # Maven multi-módulo: domain, application, adapters (lambda, dynamodb, kms, s3)
├── frontend/     # Angular (standalone, signals)
├── infra/        # templates CloudFormation, deploy.sh, teardown.sh, publish-front.mjs, id-token.mjs
├── tests/load/   # k6 y scripts de datos de la prueba de carga
├── docs/         # especificación (00 a 07)
├── tasks/        # plan, lista de tareas e informes de pruebas
├── diagramas/    # TO-BE (medical-historyv2) y PoC (medical-history-poc), .drawio y .png
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
