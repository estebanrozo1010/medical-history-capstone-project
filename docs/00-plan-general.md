# 00 — Plan general

> Estos documentos describen la **arquitectura objetivo**. La PoC se construye en el AWS Academy Learner Lab con restricciones (región, `LabRole`, sin CloudFront ni GuardDuty); las diferencias están en [07](07-learner-lab.md) y las tareas en [tasks/plan.md](../tasks/plan.md).

Índice: [01 Infra](01-infra-cloudformation.md) · [02 Backend](02-backend.md) · [03 Frontend](03-frontend.md) · [04 Datos y API](04-modelo-datos-api.md) · [05 Seguridad y pruebas](05-seguridad-y-pruebas.md)

## Convenciones compartidas (todos los docs las usan)

| Concepto | Nombre |
|---|---|
| Región | `eu-south-2` (ACM de CloudFront y WAF de CloudFront en `us-east-1`); la PoC usa `us-east-1` |
| Prefijo de recursos | `mh-` |
| Stacks CloudFormation | `mh-base`, `mh-auth`, `mh-security`, `mh-api`, `mh-edge` |
| Tablas DynamoDB | `mh-profiles`, `mh-files`, `mh-links` |
| Buckets | `mh-docs-<account>`, `mh-docs-backup-<account>`, `mh-front-<account>`, `mh-artifacts-<account>` (jars), `mh-trail-<account>` (CloudTrail) |
| Lambdas | `mh-profile`, `mh-files`, `mh-links`, `mh-postscan` |
| Estructura repo | `backend/`, `frontend/`, `infra/`, `docs/`, `diagramas/` |

## Qué necesito de ti

- Cuenta AWS con permisos de administrador para el despliegue inicial y un perfil CLI (`aws configure sso` o claves).
- Un dominio (Route 53 o delegable). Sin dominio se usa el dominio `*.cloudfront.net` y se salta Route 53/ACM.
- Instalado: Java 21, Maven, Node 20+, Angular CLI, AWS CLI v2, SAM CLI (solo para pruebas locales), `cfn-lint`.
- Decisión pendiente: si el correo del usuario se verifica con código (Cognito por defecto) o se permite registro sin verificar en la PoC.

## Fases

| # | Fase | Resultado verificable | Depende de |
|---|---|---|---|
| 0 | Estructura de repo y herramientas | `mvn -v`, `ng version`, `cfn-lint --version` OK; carpetas creadas | — |
| 1 | `mh-base` + `mh-auth` | Stack desplegado; se crea un usuario en Cognito y se obtiene un ID token | 0 |
| 2 | Backend perfil + `mh-api` mínimo | `POST/GET /profile` con JWT devuelve datos; cédula cifrada con KMS en Dynamo | 1 |
| 3 | Backend archivos + `mh-security` (GuardDuty + CloudTrail) | Subida directa a S3 con presigned; archivo queda `PENDING` y pasa a `CLEAN`/`INFECTED`; la réplica a Glacier (creada en `mh-base`) copia objetos | 2 |
| 4 | Backend links | Un link con varios archivos lista y descarga cada uno; con `activo=false` o vencido responde 410 | 3 |
| 5 | Frontend Angular | Registro, login, carpetas, subida, compartir con switch contra la API real | 2 (mock) / 4 (real) |
| 6 | `mh-edge` | App servida por CloudFront con WAF y límite por IP en `/api/s/*` | 3, 5 |
| 7 | Pruebas de carga y endurecimiento | Checklist de [05](05-seguridad-y-pruebas.md) completo | todas |

Las fases 5 (con API mockeada) y 3–4 se pueden hacer en paralelo con agentes distintos: uno backend, uno frontend. El contrato entre ambos es [04](04-modelo-datos-api.md); no debe cambiar sin actualizar ese archivo primero.

## Modelo de Claude por tarea

- **Sonnet 5.5** (por defecto): Angular, handlers Java, templates CFN rutinarios, tests.
- **Opus 5.5**: políticas IAM de mínimo privilegio, KMS (key policy), WAF, GuardDuty + `mh-postscan`, lógica de links, y la revisión de seguridad final (fase 7).
- **Haiku 5.5**: boilerplate, renombrados, formateo, textos.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Cold start de Java | Lambdas planas, Java 21 + SnapStart, memoria 1024 MB, sin Spring en el arranque |
| Disponibilidad de servicios en `eu-south-2` | Verificar con `aws ... list` en la fase 0; GuardDuty y Cognito están confirmados en el README |
| Datos médicos reales | La PoC usa datos ficticios; documentar que no cumple HIPAA/GDPR completo sin revisión |
| Errores de IAM en CFN | Desplegar por stacks pequeños, `cfn-lint` antes de cada deploy |
| Coste | Presupuesto de AWS Budgets en fase 0; borrar stacks al terminar demos |
