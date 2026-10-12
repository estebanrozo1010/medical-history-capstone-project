// Imprime el ID token de Cognito (flujo SRP, igual que la app) para probar la API con curl.
// Uso: MH_EMAIL=... MH_PASSWORD=... node infra/id-token.mjs
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { CognitoUserPool, CognitoUser, AuthenticationDetails } = require('amazon-cognito-identity-js');
const config = JSON.parse(readFileSync(new URL('../frontend/public/config.json', import.meta.url)));

const { MH_EMAIL: email, MH_PASSWORD: password } = process.env;
if (!email || !password) {
  console.error('Faltan MH_EMAIL y MH_PASSWORD');
  process.exit(1);
}
const pool = new CognitoUserPool({ UserPoolId: config.userPoolId, ClientId: config.clientId });
new CognitoUser({ Username: email, Pool: pool }).authenticateUser(
  new AuthenticationDetails({ Username: email, Password: password }),
  {
    onSuccess: (s) => console.log(s.getIdToken().getJwtToken()),
    onFailure: (e) => { console.error(e.message); process.exit(1); },
  },
);
