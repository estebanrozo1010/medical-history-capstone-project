# 06 — Skills para ejecutar el plan

Qué skill de Claude Code usar en cada momento. Las fases son las de [00](00-plan-general.md).

## Skill principal

**`/agent-skills:build`** implementa tareas de forma incremental: construir, probar, verificar y hacer commit. Con `auto` corre el plan completo en una sola pasada. Úsalo **por fase**, no todo de una vez, porque cada fase tiene su criterio de aceptación.

## Otros skills según el momento

| Cuándo | Skill | Para qué |
|---|---|---|
| Antes de empezar | `/agent-skills:plan` | Convierte las 8 fases en tareas pequeñas con criterios de aceptación y orden de dependencias. Se usa una vez al inicio. |
| Fases 2, 3 y 4 (backend) | `/agent-skills:test` | TDD: primero la prueba que falla, luego el código. Conviene en `ResolverLink`, `mh-postscan` y las reglas de autorización. |
| Fase 5 (Angular) | `frontend-design:frontend-design` | Interfaz cuidada en lugar de una genérica. |
| Antes de la fase 7 | `/agent-skills:review` y `/agent-skills:security-and-hardening` | Revisión del código y de la seguridad (con Opus 5.5). |
| Al final | `/agent-skills:ship` | Lista de verificación previa al lanzamiento. |

## Cómo empezar

1. Correr `/agent-skills:plan` para generar la lista de tareas.
2. Ejecutar la fase 0 con `/agent-skills:build` y revisarla.
3. Seguir fase por fase.

## Precaución

No usar el modo `auto` en las fases 1 y 3: crean infraestructura real en AWS y pueden generar costo. En esas fases conviene revisar cada template antes de desplegar.
