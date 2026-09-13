# Historial Médico Digital — Capstone Project

Sistema de historial médico donde el usuario puede registrarse, iniciar sesión, organizar sus documentos en carpetas por especialidad, subir archivos (PDF, imágenes, video) y compartirlos mediante un link temporal con control de expiración.

**Stack:**
- Backend: Java / Spring Boot, arquitectura hexagonal
- Frontend: Angular
- Infraestructura: AWS (PoC), región `eu-south-2` (Europe/Spain)

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
| **DynamoDB** | Perfiles de usuario y registros de links compartidos (con TTL nativo) |
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
- Cédula y fecha de nacimiento se cifran con **KMS**.

### 2.2 Subida de archivos
- El navegador **no** sube el archivo a través de API Gateway (límite de 10MB de payload y 29s de timeout).
- El flujo real: Lambda genera una **presigned URL de S3**, el navegador sube el archivo directo a S3 con esa URL.
- **Multipart upload** recomendado para archivos >100MB, obligatorio para >5GB (aplica a los videos).
- Buenas prácticas aplicadas: expiración corta de la presigned URL (5–15 min), validación de tipo/tamaño antes de emitirla, registro de cada subida para auditoría.

### 2.3 Links de acceso temporal (expiración + switch manual)
El link que se comparte **no** es una presigned URL directa (esas no se pueden revocar antes de tiempo). En su lugar:
- El link apunta a un dominio propio (CloudFront → API Gateway → Lambda de Gestión de Links) con un token.
- En DynamoDB se guarda: `token`, `activo` (true/false), `expiresAt` (atributo **TTL** nativo de Dynamo), y opcionalmente un código de confirmación.
- En cada acceso, la Lambda valida: ¿activo? ¿no expiró? Si todo pasa, genera una presigned URL de muy corta duración (1–5 min) solo para esa consulta y redirige.
- El **switch manual** (apagar el link desde la app) actualiza `activo=false` → corte inmediato.
- El **TTL** cubre la expiración automática (ej. 1–2 horas) aunque el usuario no toque el switch.

Ambos mecanismos son complementarios: uno cubre el olvido, el otro el control manual.

### 2.4 Validación de malware
Se usa **GuardDuty Malware Protection for S3** en vez de una solución propia (ej. ClamAV en Lambda):
- Se activa en la consola, sin agentes ni infraestructura propia.
- Escanea automáticamente cada objeto subido o modificado.
- Permite etiquetar/poner en cuarentena archivos infectados.
- Pago solo por los datos escaneados.

### 2.5 Backup / recuperación ante desastre
El bucket de documentos replica automáticamente (S3 Replication, mismo-región) hacia un bucket en **S3 Glacier**. Es solo para recuperación de emergencia, no para acceso frecuente — por eso se acepta el tiempo de recuperación más lento de Glacier.

### 2.6 Seguridad y auditoría
- **WAF** delante de CloudFront y API Gateway.
- **KMS** cifra en reposo S3 y DynamoDB.
- **CloudTrail** audita actividad sobre API Gateway y el bucket de documentos.

### 2.7 Región: `eu-south-2` (Europe/Spain)
- Elegida por el lugar de sustentación del proyecto (España).
- Nota técnica: el nombre comercial es "Europe (Spain)"; los datacenters físicos están en **Aragón**, no en la ciudad de Madrid. Madrid sí tiene puntos de presencia de **CloudFront** (edge locations), pero no es donde vive el cómputo/almacenamiento.
- Servicios usados confirmados disponibles en esta región: GuardDuty (desde feb. 2023) y Cognito (desde abr. 2024). CloudFront, Route 53, WAF y KMS son servicios globales sin restricción regional.

---

## 3. Estructura de carpetas sugerida

```
medical-history-capstone-project/
├── backend/         # Pending
├── frontend/           # Angular
├── diagramas/
│   ├── historial-medico-arquitectura.drawio
│   └── historial-medico-arquitectura.png
└── README.md
```

## 4. Pendientes / próximos pasos

- Definir el modelo de datos exacto de DynamoDB (tablas, claves, índices).
- Definir plantillas de infraestructura como código (CloudFormation/Terraform) para desplegar lo anterior.
- Documentar los endpoints de la API (Registro, Login, Archivos, Links).
- Pruebas de carga sobre la generación de presigned URLs y el flujo de validación de links.
