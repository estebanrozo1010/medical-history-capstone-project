# 07 — Restricciones del AWS Academy Learner Lab

Resumen de las instrucciones del laboratorio (última actualización del texto original: 24/06/2025), filtrado a lo que afecta a este proyecto. Las instrucciones pueden cambiar; ante duda, vale el texto del propio laboratorio. Los docs [00](00-plan-general.md) a [05](05-seguridad-y-pruebas.md) describen la arquitectura objetivo; lo que cambia en la PoC está en la tabla de abajo (spike de la Tarea 2 de `tasks/plan.md`).

## Entorno

- Entorno de larga duración: al terminar la sesión (temporizador en 0:00) se conservan los datos y recursos. Se puede extender la sesión con *Start Lab* antes de que llegue a 0:00.
- **Presupuesto: 50 USD.** Se ve arriba en la interfaz del laboratorio, pero viene de AWS Budgets y se actualiza cada 8 a 12 horas, así que puede ir atrasado. **Si se supera, la cuenta se desactiva y se pierden todo el progreso y los recursos.**
- *Reset* borra de forma permanente todo lo creado y **no** restablece el presupuesto.
- Credenciales temporales por sesión: se obtienen en *AWS Details*. CloudShell (icono en la consola) ya viene con las credenciales configuradas, con AWS CLI y Python 3 con boto3.

## Región

Solo `us-east-1` y `us-west-2`. Cargar la consola de un servicio en otra región da errores de acceso. **Se usa `us-east-1`** (la región por defecto del laboratorio).

## IAM

- No se pueden crear usuarios ni grupos.
- No se pueden crear roles, salvo roles vinculados a servicios. A veces se permite crear roles de servicio (puede fallar la primera vez y funcionar al reintentar), pero no hay que depender de ello.
- Existe el rol **`LabRole`** (y el perfil de instancia `LabInstanceProfile`). Se asocia a los recursos que necesiten permisos: Lambdas, API Gateway, CloudFormation, S3, KMS, DynamoDB, EventBridge, etc. Tiene permisos similares a los del usuario de la consola.
- Consecuencia para el proyecto: **una sola identidad para todo**. Las Lambdas, el rol de replicación de S3, el de GuardDuty y el de CloudTrail usan `LabRole`, pasado como parámetro `LabRoleArn` (`arn:aws:iam::<ACCOUNT_ID>:role/LabRole`) en los templates. No hay mínimo privilegio por Lambda; es un límite de la PoC.

## Servicios que aparecen en la lista del laboratorio (los que usamos)

| Servicio | Nota del laboratorio |
|---|---|
| API Gateway | Puede asumir `LabRole` |
| AWS CloudFormation | Puede asumir `LabRole`. Es la forma recomendada de trabajar: se crea el stack y, al terminar, se borra para borrar todo lo creado |
| AWS CloudTrail | Se puede crear un trail, pero **no** enviarlo a CloudWatch Logs |
| Amazon CloudWatch | Disponible |
| Amazon DynamoDB | Puede asumir `LabRole` |
| Amazon EventBridge | Puede asumir `LabRole` |
| Amazon GuardDuty | Disponible, pero Malware Protection for S3 no es viable con `LabRole` (spike) |
| AWS KMS | Puede asumir `LabRole` |
| AWS Lambda | Máximo **10 ejecuciones simultáneas**. Asociar `LabRole` a cada función |
| Amazon S3 | Puede asumir `LabRole`. **Object Lock denegado** (`s3:PutBucketObjectLockConfiguration`) al usuario y a `LabRole`, también vía CloudFormation (probado el 2026-10-16) |
| S3 Glacier | No se puede crear un bloqueo de almacén (vault lock) |
| AWS WAF | Disponible |
| Amazon Route 53 | **No se pueden registrar dominios** |
| AWS Certificate Manager | Disponible |
| AWS Secrets Manager, SNS, SQS, Step Functions, Systems Manager, STS | Disponibles (no los usamos hoy) |

## Servicios que NO aparecen en la lista

- **Amazon Cognito**
- **Amazon CloudFront**

Resultado del spike (2026-10-12): **Cognito funciona** (User Pool, App Client e ID token) y **CloudFront está denegado** (`CreateDistribution` y `CreateOriginAccessControl`). Por eso el frontend se sirve como sitio estático de S3 y la API se llama directo a API Gateway (sin WAF de CloudFront ni prefijo `/api`).

## Límites de cómputo y red

- EC2: máximo 9 instancias simultáneas y 32 vCPU. Intentar 20 o más instancias **desactiva la cuenta**. El proyecto no usa EC2.
- Superar cualquier límite de servicio puede provocar la desactivación inmediata de la cuenta y la eliminación de todos los recursos.

## Presupuesto: cómo no gastarlo

- Lo que más gasta son recursos de cómputo que se quedan encendidos (EC2, RDS, NAT Gateway, SageMaker, EMR/ECS/EKS, Elastic Beanstalk). El proyecto es serverless y no usa ninguno.
- Costos fijos probables del proyecto: **WAF** (cobra por cada ACL y cada regla al mes), **KMS** (una llave), **GuardDuty** (por GB escaneado), DynamoDB y Lambda por uso. Estimar con la calculadora de precios de AWS antes de desplegar el borde.
- Buenas prácticas: infraestructura solo con CloudFormation, borrar los stacks al terminar el día, revisar recursos olvidados con *Resource Groups & Tag Editor* (en `us-east-1` y `us-west-2`) y mirar *Trusted Advisor* para ahorro de costos.
- Algunos recursos existen desde el inicio (IAM, dos Lambdas, grupos de seguridad, VPC) y no consumen presupuesto relevante.

## Arquitectura objetivo vs. PoC en el Learner Lab

Los docs [00](00-plan-general.md) a [05](05-seguridad-y-pruebas.md) y el README describen la **arquitectura objetivo**, la que se explica en la sustentación. La **PoC** que se construye en el laboratorio difiere en lo siguiente (resultados del spike del 2026-10-12 en [tasks/plan.md](../tasks/plan.md)):

| Tema | Arquitectura objetivo | PoC en el Learner Lab | Motivo |
|---|---|---|---|
| Región | `eu-south-2` | `us-east-1` | El laboratorio solo permite `us-east-1` y `us-west-2` |
| Roles IAM | Un rol por Lambda con mínimo privilegio | `LabRole` para todo | No se pueden crear roles |
| Login | Cognito | Cognito (**sin cambios**, funciona) | — |
| Entrega del frontend | CloudFront + OAC + HTTPS | Sitio estático de S3 público, solo HTTP | CloudFront está denegado |
| WAF | CloudFront (global) y API Gateway (regional) | Ninguno (throttling del stage de API Gateway) | Sin CloudFront; el regional no se probó |
| Dominio | Route 53 + ACM | URL del sitio de S3 (`http://mh-front-<account>.s3-website-us-east-1.amazonaws.com`) y de API Gateway | No se pueden registrar dominios |
| Ruta de la API | `/api/*` en el mismo dominio vía CloudFront | URL directa de API Gateway (`https://...execute-api...`) con CORS | Sin CloudFront |
| Límite por IP en `/s/*` | Regla `rate-based` del WAF | Throttling por stage | Sin WAF de CloudFront |
| Escaneo de malware | GuardDuty Malware Protection (`CLEAN` / `INFECTED`) | Validador de tipo (extensión, MIME, primeros bytes): `CLEAN` / `REJECTED` | GuardDuty no es viable con `LabRole` |
| Comparación de tamaño declarado/real | Verificación en `mh-postscan` | No hace falta: S3 rechaza el tamaño distinto (probado) | `Content-Length` firmado funciona |
| Réplica a Glacier | S3 Replication | **Igual** (probada con `LabRole`) | — |
| CloudTrail | A CloudWatch y a un bucket S3 WORM (Object Lock Compliance, 365 días) | Solo a S3; bucket versionado con una política que niega borrar logs (*tamper-evident*, no WORM) | El laboratorio no permite CloudWatch Logs en el trail y niega Object Lock |
| Carga | 200 req/s | 8 req/s fijos (p95 < 300 ms) y una ráfaga de 30 req/s que da 429, no 5xx | Máx. 10 ejecuciones simultáneas de Lambda |

El escaneo va detrás de un puerto `ArchivoScanner` (hexagonal): el adaptador de la PoC es el validador de tipo y el de la versión objetivo es el de GuardDuty. Pasar de uno a otro no cambia el resto del backend.

## Borrado de la PoC y lo que sobrevive

`infra/teardown.sh` (Git Bash o CloudShell; pide escribir `BORRAR`, `--yes` lo salta) borra en orden inverso a `deploy.sh`: `mh-api` → `mh-edge` → `mh-security` → `mh-auth` → `mh-base`. Antes de cada stack con buckets los vacía por completo (partes multipart en curso, todas las versiones y marcas de borrado, de a 1 000), porque CloudFormation no borra un bucket con objetos; si algo escribió entre medio (CloudTrail, una subida tardía) vuelve a vaciar y reintenta una vez. Detiene el trail antes de vaciar su bucket y vacía `mh-docs` antes que `mh-docs-backup`. Al final lista stacks y buckets `mh-*` que sigan y la llave KMS. Revisar además *Resource Groups & Tag Editor* en `us-east-1`.

Ningún recurso tiene `DeletionPolicy: Retain`, DynamoDB y Cognito no tienen protección contra borrado, y los log groups están definidos en `mh-api` (se borran con él; si una Lambda alcanzó a recrear alguno, el script lo borra).

| Queda tras el borrado | Costo |
|---|---|
| Llave KMS `mh-key` en `PendingDeletion` (CloudFormation usa la ventana por defecto de 30 días; el mínimo es 7) | 0: AWS no cobra las llaves en espera de borrado. El alias se borra, así que redesplegar crea una llave nueva sin conflicto |
| Configuración de cuenta de API Gateway (rol de CloudWatch = `LabRole`) | 0 |
| Historial de stacks en `DELETE_COMPLETE` | 0 |
| Alerta de Budgets `mh-lab-50usd` (creada a mano, fuera de los stacks) | 0 (las 2 primeras son gratis) |
| Objetos borrados de `mh-docs-backup` antes de 90 días en GLACIER | Se cobra el mínimo de 90 días: centavos con los datos de prueba |
| Backups PITR de DynamoDB | Se borran con la tabla |

Redesplegar desde cero (`deploy.sh` y `node infra/publish-front.mjs`) cambia el id de API Gateway y el User Pool: `publish-front.mjs` los lee de los exports y actualiza el `config.json` del sitio, `frontend/public/config.json` y `frontend/proxy.conf.json`. Los usuarios de Cognito se pierden: hay que registrarse de nuevo.

Detalle de tareas en [tasks/plan.md](../tasks/plan.md).
