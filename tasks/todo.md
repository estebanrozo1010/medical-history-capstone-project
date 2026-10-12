# Lista de tareas

Detalle, criterios y verificación de cada tarea en [plan.md](plan.md).

## Fase 0: Cimientos y riesgos
- [x] Tarea 1: Estructura del repo y herramientas (M) — alerta de Budgets `mh-lab-50usd` creada (50 USD, avisos a 10, 20, 30 y 40 USD)
- [x] Tarea 2: Spike de riesgos de AWS (S) — resultados en plan.md; CloudFront y GuardDuty bloqueados
- [x] Tarea 3: Stack `mh-base` (M) — desplegado en us-east-1; cifrado KMS, acceso público bloqueado y réplica a GLACIER verificados
- [x] Tarea 4: Stack `mh-auth` y primer ID token (S) — desplegado; registro, confirmación y login SRP verificados, el ID token trae `sub` y `email`

### Checkpoint 1: Cimientos
- [ ] Stacks `mh-base` y `mh-auth` desplegados, spike resuelto, revisión humana

## Fase 1: Registro y perfil
- [ ] Tarea 5: Frontend base con autenticación (M)
- [ ] Tarea 6: `mh-api` y `mh-profile` (L; dividir en 6a y 6b)
- [ ] Tarea 7: Pantalla de perfil (S)

### Checkpoint 2: Primera ruta completa
- [ ] Registro → login → perfil de punta a punta, aislamiento entre usuarios, `mvn verify` y `ng test`, revisión humana

## Fase 2: Archivos
- [ ] Tarea 8: Subir y listar archivos (L; dividir en 8a backend y 8b UI)
- [ ] Tarea 9: Validación de archivos subidos (`mh-postscan`) y CloudTrail (M)
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
