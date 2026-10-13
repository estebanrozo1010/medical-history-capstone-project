// Publica el frontend en el sitio estatico de S3 (stack mh-edge): ng build, config.json con la URL real
// de la API y aws s3 sync. Uso (PowerShell o Git Bash): node infra/publish-front.mjs
// Requiere credenciales del Learner Lab en el entorno y AWS_DEFAULT_REGION=us-east-1.
import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const frontend = new URL('../frontend/', import.meta.url);
const dist = new URL('dist/historial-medico/browser/', frontend);
const run = (cmd, cwd) => execSync(cmd, { cwd, stdio: 'inherit' });
const exportado = (name) => execSync(
  `aws cloudformation list-exports --query "Exports[?Name=='${name}'].Value" --output text`,
).toString().trim();

const apiUrl = exportado('mh-api-ApiUrl');
const bucket = exportado('mh-edge-FrontBucketName');
const userPoolId = exportado('mh-auth-UserPoolId');
const clientId = exportado('mh-auth-UserPoolClientId');
if (!apiUrl.startsWith('https://') || !bucket || !userPoolId || !clientId) {
  throw new Error('Faltan los exports de mh-api, mh-edge o mh-auth');
}

// Redesplegar desde cero cambia el id de API Gateway y el User Pool: ng serve (config.json y proxy) sigue a los stacks
const writeJson = (url, edit) => writeFileSync(url, JSON.stringify(edit(JSON.parse(readFileSync(url))), null, 2) + '\n');
writeJson(new URL('public/config.json', frontend), (c) => ({ ...c, userPoolId, clientId }));
writeJson(new URL('proxy.conf.json', frontend), (p) => ({ ...p, '/v1': { ...p['/v1'], target: new URL(apiUrl).origin } }));

run('npx ng build', frontend);

// En dev apiUrl es /v1 (proxy de ng serve); el sitio publicado llama a API Gateway directo
writeJson(new URL('config.json', dist), (c) => ({ ...c, apiUrl }));

const s3 = `s3://${bucket}`;
// Sin la barra final: en Windows `\"` escaparia la comilla
const dir = `"${fileURLToPath(dist).slice(0, -1)}"`;
// 1) .js/.css llevan hash en el nombre: cache larga
run(`aws s3 sync ${dir} ${s3} --exclude "*" --include "*.js" --include "*.css" --cache-control "public, max-age=31536000, immutable" --no-progress`);
// 2) index.html, config.json y el resto: el navegador revalida siempre
run(`aws s3 sync ${dir} ${s3} --exclude "*.js" --exclude "*.css" --cache-control "no-cache" --no-progress`);
// 3) Recien ahora se borran los archivos del build anterior (el index.html nuevo ya no los usa)
run(`aws s3 sync ${dir} ${s3} --delete --no-progress`);

console.log(`Publicado: ${exportado('mh-edge-SiteUrl')}`);
