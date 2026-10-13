#!/usr/bin/env bash
# Borra todos los stacks mh-* en orden inverso al de deploy.sh, vaciando antes sus buckets
# (todas las versiones y marcas de borrado, incluido el respaldo en GLACIER). IRREVERSIBLE.
# Uso (Git Bash o CloudShell): bash infra/teardown.sh   (pide escribir BORRAR; --yes lo salta)
# Requiere credenciales del Learner Lab en el entorno y AWS_DEFAULT_REGION=us-east-1.
set -euo pipefail

# aws.exe en Git Bash: termina las líneas en \r\n (sin el tr, $ACCOUNT y los ids llevan un \r; pipefail conserva el código
# de salida) y Git Bash convierte /aws/lambda/... en una ruta de Windows (MSYS2_ARG_CONV_EXCL: solo los argumentos, no la ruta de las credenciales)
aws() { MSYS2_ARG_CONV_EXCL='*' command aws "$@" | tr -d '\r'; }

# Vacía un bucket versionado: partes multipart en curso, versiones y marcas de borrado, de a 1000
empty_bucket() {
  local bucket=$1 tmp lote
  if ! aws s3api head-bucket --bucket "$bucket" 2>/dev/null; then
    echo "  $bucket: no existe"
    return 0
  fi
  aws s3api list-multipart-uploads --bucket "$bucket" --query 'Uploads[].[Key,UploadId]' --output text |
    while IFS=$'\t' read -r key id; do
      [[ -z "$key" || "$key" == None ]] && continue
      aws s3api abort-multipart-upload --bucket "$bucket" --key "$key" --upload-id "$id"
    done
  tmp=$(mktemp)
  # aws.exe en Windows no entiende rutas /tmp de Git Bash; el lote va por archivo (límite de línea de comandos)
  lote="file://$(cygpath -m "$tmp" 2>/dev/null || echo "$tmp")"
  while :; do
    # --no-paginate: una sola llamada trae como máximo 1000 entre versiones y marcas (el límite de delete-objects)
    aws s3api list-object-versions --bucket "$bucket" --no-paginate --output json \
      --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}, Quiet: `true`}' > "$tmp"
    grep -q '"Key"' "$tmp" || break
    aws s3api delete-objects --bucket "$bucket" --delete "$lote" > /dev/null
    echo "  $bucket: lote borrado"
  done
  rm -f "$tmp"
  echo "  $bucket: vacío"
}

delete_stack() {
  local stack=$1
  if ! aws cloudformation describe-stacks --stack-name "$stack" > /dev/null 2>&1; then
    echo "$stack: no existe"
    return 0
  fi
  echo "$stack: borrando..."
  # || return 1: dentro del `if !` de delete_stack_with_buckets bash no aplica set -e
  aws cloudformation delete-stack --stack-name "$stack" || return 1
  aws cloudformation wait stack-delete-complete --stack-name "$stack" || return 1
  echo "$stack: borrado"
}

# Vacía los buckets del stack y lo borra; si algo escribió entre medio (CloudTrail, una subida tardía), reintenta una vez
delete_stack_with_buckets() {
  local stack=$1 bucket
  shift
  for bucket in "$@"; do empty_bucket "$bucket"; done
  if ! delete_stack "$stack"; then
    echo "$stack: falló el borrado, se vacían otra vez los buckets y se reintenta"
    for bucket in "$@"; do empty_bucket "$bucket"; done
    delete_stack "$stack"
  fi
}

# Al hacer `source` (pruebas) solo se cargan las funciones
[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGION=${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region || true)}}
# Los nombres de bucket son globales: desde otra región se vaciarían los buckets sin encontrar los stacks
[[ "$REGION" == us-east-1 ]] || { echo "La región es '$REGION'; usa AWS_DEFAULT_REGION=us-east-1"; exit 1; }
echo "Cuenta $ACCOUNT, región $REGION"
echo "Se borran mh-api, mh-edge, mh-security, mh-auth y mh-base con TODOS sus datos (documentos, respaldo, usuarios, tablas)."
if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "Escribe BORRAR para continuar: " respuesta
  [[ "$respuesta" == BORRAR ]] || { echo "Cancelado"; exit 1; }
fi

# Orden inverso de dependencias: mh-api importa de mh-base, mh-auth y mh-edge; mh-security importa de mh-base
delete_stack mh-api
delete_stack_with_buckets mh-edge "mh-front-$ACCOUNT"
# Sin logging el trail deja de escribir en su bucket mientras se vacía
aws cloudtrail stop-logging --name mh-trail 2>/dev/null || true
delete_stack_with_buckets mh-security "mh-trail-$ACCOUNT"
delete_stack mh-auth
# Primero el origen de la réplica, después el respaldo
delete_stack_with_buckets mh-base "mh-docs-$ACCOUNT" "mh-docs-backup-$ACCOUNT" "mh-artifacts-$ACCOUNT"

# Los log groups están en mh-api (se borran con él); si una Lambda alcanzó a recrear alguno, se borra aquí
for grupo in /aws/lambda/mh-profile /aws/lambda/mh-files /aws/lambda/mh-links /aws/lambda/mh-postscan /aws/apigateway/mh-api; do
  if [[ -n "$(aws logs describe-log-groups --log-group-name-prefix "$grupo" --query "logGroups[?logGroupName=='$grupo'].logGroupName" --output text)" ]]; then
    aws logs delete-log-group --log-group-name "$grupo"
    echo "Log group sobrante borrado: $grupo"
  fi
done

echo
echo "== Comprobación =="
echo "Stacks mh-* que siguen (debe estar vacío):"
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE UPDATE_ROLLBACK_COMPLETE \
  ROLLBACK_COMPLETE DELETE_FAILED CREATE_FAILED --query "StackSummaries[?starts_with(StackName, 'mh-')].[StackName,StackStatus]" --output text
echo "Buckets mh-* que siguen (debe estar vacío):"
aws s3 ls | grep -E " mh-[a-z-]+-$ACCOUNT$" || true
echo "Llaves KMS en espera de borrado (sin costo mientras esperan; CloudFormation usa la ventana de 30 días):"
for key in $(aws kms list-keys --query 'Keys[].KeyId' --output text); do
  aws kms describe-key --key-id "$key" --query "KeyMetadata.[KeyId,KeyState,DeletionDate,Description]" --output text 2>/dev/null |
    grep -E 'PendingDeletion.*mh-key' || true
done
echo "Queda sin costo: la configuración de cuenta de API Gateway (rol de CloudWatch = LabRole), el historial de stacks borrados y la alerta de Budgets."
echo "Revisar también Resource Groups & Tag Editor en us-east-1."
