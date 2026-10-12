#!/usr/bin/env bash
# Despliegue de los stacks en orden (se completa en las Tareas 3, 4, 6, 9 y 16).
# Requiere credenciales del Learner Lab en el entorno y AWS_DEFAULT_REGION=us-east-1.
set -euo pipefail
cfn-lint "$(dirname "$0")"/*.yaml
