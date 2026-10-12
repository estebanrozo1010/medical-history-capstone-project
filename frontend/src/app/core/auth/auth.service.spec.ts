import { TestBed } from '@angular/core/testing';
import { CognitoUser, CognitoUserPool } from 'amazon-cognito-identity-js';
import { Config } from '../config';
import { AuthService } from './auth.service';

const sesion = (jwt: string, expSeg: number, refresh = 'refresh-1') =>
  ({
    getIdToken: () => ({ getJwtToken: () => jwt, getExpiration: () => expSeg }),
    getRefreshToken: () => ({ getToken: () => refresh }),
  }) as never;
const enUnaHora = () => Math.floor(Date.now() / 1000) + 3600;
const hacePoco = () => Math.floor(Date.now() / 1000) - 10;

describe('AuthService', () => {
  let auth: AuthService;

  const crear = () => {
    TestBed.resetTestingModule();
    TestBed.inject(Config).value = {
      apiUrl: '',
      userPoolId: 'us-east-1_abc123',
      clientId: 'cliente',
      region: 'us-east-1',
    };
    auth = TestBed.inject(AuthService);
  };
  const loginOk = (s: unknown) =>
    vi.spyOn(CognitoUser.prototype, 'authenticateUser').mockImplementation((_d, cb) => cb.onSuccess(s as never));

  beforeEach(() => {
    sessionStorage.clear();
    crear();
  });
  afterEach(() => vi.restoreAllMocks());

  it('idToken() es null sin sesión', async () => {
    expect(await auth.idToken()).toBeNull();
  });

  it('login guarda el ID token en memoria y el refresh token en sessionStorage', async () => {
    loginOk(sesion('jwt-1', enUnaHora()));

    await auth.login('a@b.co', 'Clave-1234');

    expect(await auth.idToken()).toBe('jwt-1');
    expect(auth.email()).toBe('a@b.co');
    expect(JSON.parse(sessionStorage.getItem('mh.session')!)).toEqual({ email: 'a@b.co', refreshToken: 'refresh-1' });
    expect(JSON.stringify(localStorage)).not.toContain('jwt-1');
  });

  it('login rechaza con el error de Cognito y no deja sesión', async () => {
    vi.spyOn(CognitoUser.prototype, 'authenticateUser').mockImplementation((_d, cb) =>
      cb.onFailure({ code: 'NotAuthorizedException' }),
    );

    await expect(auth.login('a@b.co', 'mala')).rejects.toMatchObject({ code: 'NotAuthorizedException' });
    expect(await auth.idToken()).toBeNull();
    expect(sessionStorage.getItem('mh.session')).toBeNull();
  });

  it('con el token vencido lo renueva con el refresh token', async () => {
    loginOk(sesion('jwt-viejo', hacePoco()));
    const refrescar = vi
      .spyOn(CognitoUser.prototype, 'refreshSession')
      .mockImplementation((_t, cb) => cb(null, sesion('jwt-nuevo', enUnaHora())));
    await auth.login('a@b.co', 'Clave-1234');

    expect(await auth.idToken()).toBe('jwt-nuevo');
    expect(refrescar).toHaveBeenCalledTimes(1);
  });

  it('tras recargar la página restaura la sesión desde el refresh token', async () => {
    sessionStorage.setItem('mh.session', JSON.stringify({ email: 'a@b.co', refreshToken: 'refresh-9' }));
    crear();
    vi.spyOn(CognitoUser.prototype, 'refreshSession').mockImplementation((t, cb) => {
      expect(t.getToken()).toBe('refresh-9');
      cb(null, sesion('jwt-restaurado', enUnaHora()));
    });

    expect(await auth.idToken()).toBe('jwt-restaurado');
    expect(auth.email()).toBe('a@b.co');
  });

  it('si el refresh falla limpia la sesión', async () => {
    sessionStorage.setItem('mh.session', JSON.stringify({ email: 'a@b.co', refreshToken: 'caducado' }));
    crear();
    vi.spyOn(CognitoUser.prototype, 'refreshSession').mockImplementation((_t, cb) =>
      cb({ code: 'NotAuthorizedException' } as never, null as never),
    );

    expect(await auth.idToken()).toBeNull();
    expect(sessionStorage.getItem('mh.session')).toBeNull();
    expect(auth.email()).toBeNull();
  });

  it('logout borra tokens y sessionStorage', async () => {
    loginOk(sesion('jwt-1', enUnaHora()));
    await auth.login('a@b.co', 'Clave-1234');

    auth.logout();

    expect(await auth.idToken()).toBeNull();
    expect(sessionStorage.getItem('mh.session')).toBeNull();
  });

  it('signUp y confirm llaman a Cognito y rechazan con su error', async () => {
    const signUp = vi
      .spyOn(CognitoUserPool.prototype, 'signUp')
      .mockImplementation((_u, _p, _a, _v, cb) => cb(undefined, {} as never));
    await auth.signUp('a@b.co', 'Clave-1234');
    expect(signUp.mock.calls[0][0]).toBe('a@b.co');

    vi.spyOn(CognitoUser.prototype, 'confirmRegistration').mockImplementation((_c, _f, cb) =>
      cb({ code: 'CodeMismatchException' } as never, null),
    );
    await expect(auth.confirm('a@b.co', '000000')).rejects.toMatchObject({ code: 'CodeMismatchException' });
  });
});
