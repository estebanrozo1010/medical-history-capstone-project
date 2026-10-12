import { Injectable, inject, signal } from '@angular/core';
import {
  AuthenticationDetails,
  CognitoRefreshToken,
  CognitoUser,
  CognitoUserAttribute,
  CognitoUserPool,
  CognitoUserSession,
  ICognitoStorage,
} from 'amazon-cognito-identity-js';
import { Config } from '../config';

const CLAVE = 'mh.session';
const MARGEN_SEG = 30;

/** La librería cachea tokens en localStorage por defecto; aquí se quedan en memoria. */
const memoria = new Map<string, string>();
const almacenMemoria: ICognitoStorage = {
  setItem: (k, v) => void memoria.set(k, v),
  getItem: (k) => memoria.get(k) as string,
  removeItem: (k) => void memoria.delete(k),
  clear: () => memoria.clear(),
};

const promesa = <T>(fn: (cb: (err: unknown, res?: T) => void) => void) =>
  new Promise<T>((ok, fail) => fn((err, res) => (err ? fail(err) : ok(res as T))));

/**
 * ID token en memoria; refresh token en sessionStorage (límite aceptado de la PoC:
 * un XSS podría leerlo, pero no sobrevive al cerrar la pestaña).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(Config);
  private _pool?: CognitoUserPool;
  private idJwt: string | null = null;
  private idExp = 0;
  private enCurso: Promise<string | null> | null = null;

  readonly email = signal<string | null>(null);

  private get pool() {
    return (this._pool ??= new CognitoUserPool({
      UserPoolId: this.config.value.userPoolId,
      ClientId: this.config.value.clientId,
      Storage: almacenMemoria,
    }));
  }

  private usuario(email: string) {
    return new CognitoUser({ Username: email, Pool: this.pool, Storage: almacenMemoria });
  }

  signUp(email: string, password: string) {
    return promesa((cb) => this.pool.signUp(email, password, [new CognitoUserAttribute({ Name: 'email', Value: email })], [], cb));
  }

  confirm(email: string, codigo: string) {
    return promesa((cb) => this.usuario(email).confirmRegistration(codigo, true, cb));
  }

  resendCode(email: string) {
    return promesa((cb) => this.usuario(email).resendConfirmationCode(cb));
  }

  async login(email: string, password: string) {
    const sesion = await new Promise<CognitoUserSession>((onSuccess, onFailure) =>
      this.usuario(email).authenticateUser(new AuthenticationDetails({ Username: email, Password: password }), {
        onSuccess,
        onFailure,
      }),
    );
    this.guardar(sesion);
    this.email.set(email);
    sessionStorage.setItem(CLAVE, JSON.stringify({ email, refreshToken: sesion.getRefreshToken().getToken() }));
  }

  logout() {
    const email = this.email();
    if (email) this.usuario(email).signOut();
    memoria.clear();
    this.idJwt = null;
    this.idExp = 0;
    this.email.set(null);
    sessionStorage.removeItem(CLAVE);
  }

  /** ID token vigente, renovándolo si venció o restaurándolo tras recargar; null si no hay sesión. */
  async idToken(): Promise<string | null> {
    if (this.idJwt && this.idExp - MARGEN_SEG > Date.now() / 1000) return this.idJwt;
    return this.refresh();
  }

  /** Fuerza la renovación con el refresh token. Si falla, la sesión se descarta. */
  refresh(): Promise<string | null> {
    return (this.enCurso ??= this.renovar().finally(() => (this.enCurso = null)));
  }

  private async renovar(): Promise<string | null> {
    const guardada = this.leer();
    if (!guardada) return null;
    try {
      const sesion = await promesa<CognitoUserSession>((cb) =>
        this.usuario(guardada.email).refreshSession(new CognitoRefreshToken({ RefreshToken: guardada.refreshToken }), cb),
      );
      this.guardar(sesion);
      this.email.set(guardada.email);
      return this.idJwt;
    } catch {
      this.logout();
      return null;
    }
  }

  private guardar(sesion: CognitoUserSession) {
    this.idJwt = sesion.getIdToken().getJwtToken();
    this.idExp = sesion.getIdToken().getExpiration();
  }

  private leer(): { email: string; refreshToken: string } | null {
    try {
      return JSON.parse(sessionStorage.getItem(CLAVE) ?? 'null');
    } catch {
      return null;
    }
  }
}
