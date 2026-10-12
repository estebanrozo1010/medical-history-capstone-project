#!/usr/bin/env bash
# Despliegue de los stacks en orden (se completa en la Tarea 16).
# Requiere credenciales del Learner Lab en el entorno y AWS_DEFAULT_REGION=us-east-1.
set -euo pipefail
cd "$(dirname "$0")"
cfn-lint ./*.yaml

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT}:role/LabRole"

aws cloudformation deploy --stack-name mh-base --template-file base.yaml \
  --parameter-overrides "LabRoleArn=${LAB_ROLE_ARN}" --no-fail-on-empty-changeset

aws cloudformation deploy --stack-name mh-auth --template-file auth.yaml --no-fail-on-empty-changeset

# CloudTrail a S3 (sin CloudWatch Logs ni GuardDuty en el Learner Lab)
aws cloudformation deploy --stack-name mh-security --template-file security.yaml --no-fail-on-empty-changeset

# mh-api: jar sombreado de las Lambdas, subido al bucket de artefactos de mh-base
mvn -q -B -f ../backend/pom.xml package -pl adapter-in-lambda -am
ARTIFACTS=$(aws cloudformation list-exports \
  --query "Exports[?Name=='mh-base-ArtifactsBucketName'].Value" --output text)
PACKAGED=$(mktemp)
aws cloudformation package --template-file api.yaml --s3-bucket "$ARTIFACTS" \
  --output-template-file "$PACKAGED"
aws cloudformation deploy --stack-name mh-api --template-file "$PACKAGED" \
  --capabilities CAPABILITY_AUTO_EXPAND \
  --parameter-overrides "LabRoleArn=${LAB_ROLE_ARN}" --no-fail-on-empty-changeset
rm -f "$PACKAGED"
