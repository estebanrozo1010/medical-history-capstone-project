# 06 — Skills para ejecutar el plan

Qué skill de Claude Code usar en cada momento. Las fases son las de [00](00-plan-general.md).

## Skill principal

**`/agent-skills:build`** implementa tareas de forma incremental: construir, probar, verificar y hacer commit. Con `auto` corre el plan completo en una sola pasada. En este proyecto se usó **por tarea** del [plan](../tasks/plan.md) (`/agent-skills:build Tarea N del plan (...)`), con un prompt que dice qué leer, el alcance, lo que queda fuera y cómo verificar; el de la Tarea 15 está en el plan como ejemplo. Las tareas grandes se dividieron al implementarlas (6a/6b, 8a/8b, etc.).

## Otros skills según el momento

| Cuándo | Skill | Para qué |
|---|---|---|
| Antes de empezar | `/agent-skills:plan` | Convierte las 8 fases en tareas pequeñas con criterios de aceptación y orden de dependencias. Se usa una vez al inicio. |
| Fases 2, 3 y 4 (backend) | `/agent-skills:test` | TDD: primero la prueba que falla, luego el código. Conviene en `ResolverLink`, `mh-postscan` y las reglas de autorización. |
| Fase 5 (Angular) | `frontend-design:frontend-design` | Interfaz cuidada en lugar de una genérica. |
| Antes de la fase 7 | `/agent-skills:review` y `/agent-skills:security-and-hardening` | Revisión del código y de la seguridad (con Opus 5.5). |
| Al final | `/agent-skills:ship` | Lista de verificación previa al lanzamiento. Lanza en paralelo revisión de código, de seguridad y de pruebas; el resultado (go/no-go) quedó en la Tarea 19 del plan. |

## Cómo empezar

1. Correr `/agent-skills:plan` para generar la lista de tareas (hecho: [tasks/plan.md](../tasks/plan.md) y [tasks/todo.md](../tasks/todo.md)).
2. Ejecutar cada tarea con `/agent-skills:build` y revisarla; en los checkpoints, revisión con una persona.
3. Anotar en el plan lo probado, las decisiones y lo que queda para la tarea siguiente.

## Precaución

No usar el modo `auto` en las fases 1 y 3: crean infraestructura real en AWS y pueden generar costo. En esas fases conviene revisar cada template antes de desplegar.
