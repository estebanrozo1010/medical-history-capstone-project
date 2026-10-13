import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from '../auth/auth.interceptor';
import { Config } from '../config';
import { Link, LinksApi } from './links.api';

const API = 'https://api.test/v1';
const turno = () => new Promise((r) => setTimeout(r));
const VENCE = Math.floor(Date.now() / 1000) + 3600;
const link = (token: string, activo = true): Link => ({ token, archivos: [{ fileId: 'f1', nombre: 'a.pdf' }], activo, expiresAt: VENCE });

describe('LinksApi', () => {
  let api: LinksApi;
  let backend: HttpTestingController;
  const email = signal<string | null>('a@x.co');

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { idToken: async () => 'tok', refresh: async () => 'tok', logout: () => {}, email } },
      ],
    });
    TestBed.inject(Config).value = { apiUrl: API, userPoolId: '', clientId: '', region: '' };
    api = TestBed.inject(LinksApi);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  const crear = async (token: string) => {
    let creado: unknown;
    api.crear([{ fileId: 'f1', nombre: 'a.pdf' }, { fileId: 'f2', nombre: 'b.pdf' }], 60).subscribe((c) => (creado = c));
    await turno();
    const req = backend.expectOne(`${API}/links`);
    req.flush({ token, url: `http://localhost:4200/compartido/${token}`, expiresAt: VENCE }, { status: 201, statusText: 'Created' });
    return { req, creado };
  };

  it('crear hace POST {apiUrl}/links con los fileIds y ttlMinutos, con el token', async () => {
    const { req, creado } = await crear('t1');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
    expect(req.request.body).toEqual({ fileIds: ['f1', 'f2'], ttlMinutos: 60 });
    expect(creado).toEqual({ token: 't1', url: 'http://localhost:4200/compartido/t1', expiresAt: VENCE });
  });

  it('listar agrega primero el link recién creado que GET /links aún no trae (lectura eventual)', async () => {
    await crear('nuevo');
    let lista: Link[] = [];
    api.listar().subscribe((l) => (lista = l));
    await turno();
    backend.expectOne(`${API}/links`).flush([link('viejo')]);

    expect(lista.map((l) => l.token)).toEqual(['nuevo', 'viejo']);
    expect(lista[0]).toEqual({ token: 'nuevo', activo: true, expiresAt: VENCE,
      archivos: [{ fileId: 'f1', nombre: 'a.pdf' }, { fileId: 'f2', nombre: 'b.pdf' }] });
  });

  it('cuando GET /links ya lo trae, se usa el del backend y no se duplica', async () => {
    await crear('nuevo');
    let lista: Link[] = [];
    api.listar().subscribe((l) => (lista = l));
    await turno();
    backend.expectOne(`${API}/links`).flush([link('nuevo', false)]);

    expect(lista).toEqual([link('nuevo', false)]);
  });

  it('cambiarEstado hace PATCH {apiUrl}/links/{token} y lo refleja en el link recordado', async () => {
    await crear('a/b');
    api.cambiarEstado('a/b', false).subscribe();
    await turno();
    const req = backend.expectOne(`${API}/links/a%2Fb`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ activo: false });
    req.flush({ token: 'a/b', activo: false, expiresAt: VENCE });

    let lista: Link[] = [];
    api.listar().subscribe((l) => (lista = l));
    await turno();
    backend.expectOne(`${API}/links`).flush([]);
    expect(lista[0].activo).toBe(false);
  });

  it('al cambiar de usuario (logout y otro login) olvida los links recordados', async () => {
    await crear('de-a');
    email.set('b@x.co');
    let lista: Link[] = [];
    api.listar().subscribe((l) => (lista = l));
    await turno();
    backend.expectOne(`${API}/links`).flush([]);
    expect(lista).toEqual([]);
  });
});
