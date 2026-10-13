import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';
import { authInterceptor } from '../../../core/auth/auth.interceptor';
import { AuthService } from '../../../core/auth/auth.service';
import { Config } from '../../../core/config';
import { Compartido } from './compartido';

const API = 'https://api.test/v1';
const TOKEN = 'tok_abc-123';
const EXPIRA = Math.floor(Date.now() / 1000) + 3600;
const archivo = (fileId: string, nombre: string, size = 1000) => ({ fileId, nombre, contentType: 'application/pdf', size });

describe('Compartido (pantalla pública)', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;
  // Con sesión iniciada: si el interceptor tocara la llamada, le pondría el token
  const auth = { idToken: vi.fn().mockResolvedValue('tok-sesion'), refresh: vi.fn(), logout: vi.fn() };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    TestBed.inject(Config).value = { apiUrl: API, userPoolId: '', clientId: '', region: '' };
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => {
    backend.verify();
    vi.restoreAllMocks();
  });

  const abrir = async () => {
    await harness.navigateByUrl(`/compartido/${TOKEN}`, Compartido);
    return harness.routeNativeElement!;
  };
  const responder = async (body: object | null, status = 200) => {
    const req = backend.expectOne(`${API}/s/${TOKEN}`);
    req.flush(body, { status, statusText: 'x' });
    await harness.fixture.whenStable();
    harness.detectChanges();
    return req;
  };
  const volver = async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await harness.fixture.whenStable();
  };

  it('abre sin sesión ni guard y pide GET /s/{token} sin Authorization', async () => {
    auth.idToken.mockResolvedValueOnce(null);
    const el = await abrir();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Cargando');
    const req = await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [] });
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.has('Authorization')).toBe(false);
  });

  it('con sesión iniciada tampoco manda Authorization', async () => {
    await abrir();
    await new Promise((r) => setTimeout(r));
    const req = await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [] });
    expect(req.request.headers.has('Authorization')).toBe(false);
  });

  it('lista los archivos por nombre con tamaño, vencimiento y enlaces Ver y Descargar', async () => {
    const el = await abrir();
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [archivo('f2', 'Rayos X.png', 2048), archivo('f1', 'análisis.pdf', 500)] });

    const filas = [...el.querySelectorAll('li')];
    expect(filas.map((f) => f.querySelector('.nombre')?.textContent)).toEqual(['análisis.pdf', 'Rayos X.png']);
    expect(filas[0].textContent).toContain('500 B');
    expect(filas[1].textContent).toContain('2 KB');
    expect(el.textContent).toContain('Disponible hasta');

    const ver = el.querySelector<HTMLAnchorElement>('a[aria-label="Ver análisis.pdf (se abre en otra pestaña)"]')!;
    expect(ver.href).toBe(`${API}/s/${TOKEN}/files/f1?modo=ver`);
    expect(ver.target).toBe('_blank');
    expect(ver.rel).toBe('noopener');
    const descargar = el.querySelector<HTMLAnchorElement>('a[aria-label="Descargar análisis.pdf"]')!;
    expect(descargar.href).toBe(`${API}/s/${TOKEN}/files/f1`);
    expect(descargar.target).toBe('');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it.each([
    ['ver', ['Ver']],
    ['descargar', ['Descargar']],
    ['ambos', ['Ver', 'Descargar']],
  ])('con permiso %s muestra solo %j', async (permiso, enlaces) => {
    const el = await abrir();
    await responder({ expiresAt: EXPIRA, permiso, archivos: [archivo('f1', 'a.pdf')] });
    expect([...el.querySelectorAll('li a')].map((a) => a.textContent!.trim())).toEqual(enlaces);
  });

  it('ante 410 muestra "enlace no disponible"', async () => {
    const el = await abrir();
    await responder({ code: 'NO_DISPONIBLE', message: 'x' }, 410);
    expect(el.querySelector('h1')?.textContent).toContain('Enlace no disponible');
    expect(el.querySelector('li')).toBeNull();
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('con la lista vacía muestra su propio mensaje', async () => {
    const el = await abrir();
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [] });
    expect(el.textContent).toContain('ya no están disponibles');
    expect(el.querySelector('ul')).toBeNull();
  });

  it.each([500, 0])('ante error %i avisa con role=alert', async (status) => {
    const el = await abrir();
    await responder(null, status);
    expect(el.querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar');
  });

  it('al volver a la pestaña recarga la lista sin volver a "Cargando"', async () => {
    const el = await abrir();
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [archivo('f1', 'a.pdf'), archivo('f2', 'b.pdf')] });

    await volver();
    harness.detectChanges();
    expect(el.textContent).not.toContain('Cargando');
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [archivo('f2', 'b.pdf')] });
    expect([...el.querySelectorAll('.nombre')].map((n) => n.textContent)).toEqual(['b.pdf']);
  });

  it('si al volver ya no está disponible, lo dice y lleva el foco al título', async () => {
    const el = await abrir();
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [archivo('f1', 'a.pdf')] });
    el.querySelector<HTMLAnchorElement>('a')!.focus();

    await volver();
    await responder({ code: 'NO_DISPONIBLE' }, 410);
    await harness.fixture.whenStable();
    expect(el.querySelector('h1')?.textContent).toContain('Enlace no disponible');
    expect(document.activeElement).toBe(el.querySelector('h1'));
  });

  it('no recarga si la pestaña sigue oculta', async () => {
    await abrir();
    await responder({ expiresAt: EXPIRA, permiso: 'ambos', archivos: [] });
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    backend.expectNone(`${API}/s/${TOKEN}`);
  });
});
