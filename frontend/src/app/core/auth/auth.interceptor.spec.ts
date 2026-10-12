import { provideHttpClient, withInterceptors, HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Config } from '../config';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

const API = 'https://api.test/v1';
const turno = () => new Promise((r) => setTimeout(r));

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  const auth = { idToken: vi.fn(), refresh: vi.fn(), logout: vi.fn() };

  const preparar = (apiUrl = API) => {
    auth.idToken.mockResolvedValue('tok-1');
    auth.refresh.mockResolvedValue('tok-2');
    auth.logout.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    TestBed.inject(Config).value = { apiUrl, userPoolId: '', clientId: '', region: '' };
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  };

  afterEach(() => backend.verify());

  it('añade el ID token a las llamadas a apiUrl', async () => {
    preparar();
    http.get(`${API}/profile`).subscribe();
    await turno();

    expect(backend.expectOne(`${API}/profile`).request.headers.get('Authorization')).toBe('Bearer tok-1');
  });

  it('no añade el token a otras URLs (p. ej. S3)', async () => {
    preparar();
    http.put('https://bucket.s3.amazonaws.com/x', 'datos').subscribe();
    await turno();

    expect(backend.expectOne('https://bucket.s3.amazonaws.com/x').request.headers.has('Authorization')).toBe(false);
  });

  it('con apiUrl vacío no añade el token a ninguna llamada', async () => {
    preparar('');
    http.get('https://otro.test/x').subscribe();
    await turno();

    expect(backend.expectOne('https://otro.test/x').request.headers.has('Authorization')).toBe(false);
  });

  it('ante 401 renueva el token y reintenta una vez', async () => {
    preparar();
    let respuesta: unknown;
    http.get(`${API}/profile`).subscribe((r) => (respuesta = r));
    await turno();
    backend.expectOne(`${API}/profile`).flush('', { status: 401, statusText: 'Unauthorized' });
    await turno();

    const reintento = backend.expectOne(`${API}/profile`);
    expect(reintento.request.headers.get('Authorization')).toBe('Bearer tok-2');
    reintento.flush({ ok: true });
    expect(respuesta).toEqual({ ok: true });
  });

  it('si el reintento también da 401 cierra la sesión, va a /login y propaga el error', async () => {
    preparar();
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    let estado = 0;
    http.get(`${API}/profile`).subscribe({ error: (e) => (estado = e.status) });
    await turno();
    backend.expectOne(`${API}/profile`).flush('', { status: 401, statusText: 'Unauthorized' });
    await turno();
    backend.expectOne(`${API}/profile`).flush('', { status: 401, statusText: 'Unauthorized' });
    await turno();

    expect(estado).toBe(401);
    expect(auth.logout).toHaveBeenCalled();
    expect(navegar).toHaveBeenCalledWith('/login');
  });
});
