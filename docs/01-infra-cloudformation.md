# 01 — Infraestructura con CloudFormation

Ruta: `infra/`. Un template por stack, parámetros comunes `Env` (`dev`/`prod`) y `Prefix` (`mh`). Nombres según [00](00-plan-general.md).

## Orden de despliegue

`mh-base` → `mh-auth` → `mh-security` → `mh-api` → `mh-edge`

Los stacks se comunican por `Outputs` + `Export` (`mh-base-DocsBucketArn`, etc.) o por parámetros en el script de despliegue.

## Stacks

### `mh-base` (`infra/base.yaml`)
- **KMS**: una CMK `mh-key` con rotación anual y alias. Key policy: administración para el rol de despliegue y una sentencia que **delega en IAM** (principal raíz de la cuenta). Cada rol recibe sus permisos `kms:*` en su propia política de IAM (en `mh-api` / `mh-security`), así `mh-base` no depende de roles creados después. Servicios que necesitan permiso explícito en la key policy: S3 (replicación), GuardDuty Malware Protection (`kms:Decrypt`/`GenerateDataKey` para leer y etiquetar los objetos) y CloudTrail (cifrado de logs).
- **S3 documentos** `mh-docs-<account>`: SSE-KMS con `BucketKeyEnabled`, versionado ON (requisito de replicación), Block Public Access total, política que exige TLS, CORS solo para el dominio del front (PUT/GET/HEAD, expone `ETag` para multipart; `http://localhost:4200` solo en `dev`), lifecycle que aborta multiparts incompletos a los 7 días y elimina versiones no vigentes a los 30 días.
- **S3 backup** `mh-docs-backup-<account>`: destino de replicación en misma región, clase de almacenamiento `GLACIER` (o `DEEP_ARCHIVE`), SSE-KMS, versionado ON. Rol de replicación IAM con `kms:Decrypt` (origen) y `kms:Encrypt` (destino); la regla debe incluir `SourceSelectionCriteria: SseKmsEncryptedObjects`. La réplica copia el objeto al llegar, antes del escaneo: por eso `mh-postscan` borra también del respaldo si el archivo resulta infectado (ver [02](02-backend.md)). *Alternativa a verificar:* filtrar la regla por la etiqueta `GuardDutyMalwareScanStatus=NO_THREATS_FOUND`; no se adopta hasta confirmar que S3 replica objetos cuya etiqueta cambia después de la creación.
- **S3 artefactos** `mh-artifacts-<account>`: jars de las Lambdas que sube `aws cloudformation package` en `infra/deploy.sh`; privado, SSE-S3, TLS obligatorio, borra objetos a los 30 días.
- **S3 frontend** `mh-front-<account>`: privado, acceso solo vía CloudFront OAC (la política se añade en `mh-edge`).
- **DynamoDB**: `mh-profiles`, `mh-files`, `mh-links` (esquemas en [04](04-modelo-datos-api.md); `mh-files` lleva el LSI `byCarpeta`, que solo se puede definir al crear la tabla), `PAY_PER_REQUEST`, SSE con la CMK, PITR ON, TTL en `mh-links.expiresAt`.

### `mh-auth` (`infra/auth.yaml`)
- User Pool: login por email, verificación por código, política de contraseña razonable, MFA opcional.
- App Client público (sin secreto) para Angular, flujo `USER_SRP_AUTH`, tokens: access 1 h, refresh 30 d.
- Atributos estándar solamente (email); el resto del perfil va en DynamoDB.
- El front envía el **ID token** (trae `email`; el access token no) y el authorizer de API Gateway se configura sin scopes.
- Outputs: `UserPoolId`, `UserPoolClientId`, `UserPoolArn`.

### `mh-security` (`infra/security.yaml`)
**PoC en el Learner Lab (lo implementado):** solo CloudTrail. Trail `mh-trail` (una región, validación de archivos de log) con eventos de gestión y eventos de datos de lectura y escritura de objetos del bucket de documentos, hacia `mh-trail-<account>` (privado, TLS obligatorio, borra a los 30 días). Cifrado SSE-S3: usar la CMK exige una sentencia para `cloudtrail.amazonaws.com` en la key policy de `mh-base`, se deja para la versión objetivo. Sin GuardDuty (no viable con `LabRole`) ni CloudWatch Logs (el laboratorio no lo permite); el disparo de `mh-postscan` es la regla `Object Created` de `mh-api` ([02](02-backend.md)).

**Versión objetivo:**
- **GuardDuty Malware Protection for S3** sobre `mh-docs-<account>` (`AWS::GuardDuty::MalwareProtectionPlan`, con rol de servicio y `TagScanResult: true`). *Verificar que el recurso y los campos existan en `eu-south-2` al escribir el template.*
- **CloudTrail**: trail con eventos de datos del bucket de documentos y eventos de gestión, hacia un bucket de logs propio cifrado con la CMK. Ojo: CloudTrail solo registra cambios de configuración de API Gateway, no cada petición; para eso están los access logs del stage (ver `mh-api`).
- **EventBridge rule**: evento `GuardDuty Malware Protection Object Scan Result` → Lambda `mh-postscan` (el target se conecta en `mh-api`, la regla puede vivir allí).

### `mh-api` (`infra/api.yaml`, con SAM `Transform` si se prefiere)
- 4 Lambdas Java 21 (`mh-profile`, `mh-files`, `mh-links`, `mh-postscan`), `SnapStart: ApplyOn: PublishedVersions`, `AutoPublishAlias: live`, 1024 MB, timeout 15 s (postscan 60 s).
- Un rol IAM por Lambda con solo lo necesario:
  - profile: `dynamodb:GetItem/PutItem/UpdateItem` en `mh-profiles`, `kms:Encrypt/Decrypt/GenerateDataKey`.
  - files: `s3:PutObject/GetObject/AbortMultipartUpload` sobre `mh-docs/*` (para firmar), `s3:ListBucketVersions` + `s3:DeleteObjectVersion` en los dos buckets (borrar archivos), DynamoDB en `mh-files`, KMS.
  - links: lectura (`GetItem`/`BatchGetItem`) de `mh-files`, lectura/escritura de `mh-links`, `s3:GetObject` (para firmar descarga), KMS.
  - postscan: `s3:GetObjectTagging`, `s3:GetObject` (cabecera/`HeadObject`), `s3:ListBucketVersions` + `s3:DeleteObjectVersion` en los dos buckets, `dynamodb:UpdateItem` en `mh-files`, KMS.
- API Gateway REST, stage `v1`, con authorizer Cognito (ID token) en todas las rutas excepto las públicas `GET /api/s/{token}` y `GET /api/s/{token}/files/{fileId}`. Todas las rutas cuelgan de `/api` (ver `mh-edge`). Throttling por stage. Rutas en [04](04-modelo-datos-api.md).
- WAF **REGIONAL** asociado al stage.
- **Access logs** del stage en CloudWatch (JSON: `sub`, método, ruta, estado, IP, latencia; sin tokens) — es la auditoría de las peticiones.
- Log groups con retención 30 días.

### `mh-edge` (`infra/edge.yaml`, parte en `us-east-1`)
- Certificado ACM y WebACL de WAF con `Scope: CLOUDFRONT` **deben crearse en `us-east-1`** → template separado `infra/edge-us-east-1.yaml` desplegado primero.
- CloudFront: origen por defecto S3 front con OAC (SPA: error 403/404 → `/index.html`), comportamiento `/api/*` → API Gateway (sin caché, reenvía `Authorization`) con `OriginPath: /v1` (el stage), de modo que `/api/x` llega a API Gateway como `/v1/api/x`; headers de seguridad (HSTS, CSP con `connect-src` para Cognito y S3, X-Content-Type-Options).
- WAF de CloudFront: reglas gestionadas más una regla `rate-based` por IP para `/api/s/*` (p. ej. 100 peticiones / 5 min). API Gateway no limita por IP ni por usuario.
- Route 53: alias A/AAAA al distribution (omitir si no hay dominio).

## Despliegue

```bash
cfn-lint infra/*.yaml
aws cloudformation deploy --stack-name mh-base --template-file infra/base.yaml \
  --capabilities CAPABILITY_NAMED_IAM --region eu-south-2
# repetir en el orden indicado; script: infra/deploy.sh
```

## Criterios de aceptación
- `cfn-lint` sin errores en todos los templates.
- Cada stack se despliega y se borra limpio (los buckets se vacían antes de borrar).
- Ningún bucket es público; `aws s3api get-bucket-encryption` muestra KMS.
- No existe ninguna política IAM con `Resource: "*"` salvo las acciones que AWS lo exige.
