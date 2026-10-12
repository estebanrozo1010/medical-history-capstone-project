#!/usr/bin/env bash
# Despliegue de los stacks en orden (se completa en las Tareas 6, 9 y 16).
# Requiere credenciales del Learner Lab en el entorno y AWS_DEFAULT_REGION=us-east-1.
set -euo pipefail
cd "$(dirname "$0")"
cfn-lint ./*.yaml

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT}:role/LabRole"

aws cloudformation deploy --stack-name mh-base --template-file base.yaml \
  --parameter-overrides "LabRoleArn=${LAB_ROLE_ARN}" --no-fail-on-empty-changeset

aws cloudformation deploy --stack-name mh-auth --template-file auth.yaml --no-fail-on-empty-changeset
