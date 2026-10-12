import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ProfileApi } from '../core/api/profile.api';
import { AuthService } from '../core/auth/auth.service';
import { Perfil } from './perfil';

const PERFIL = { email: 'a@b.co', nombre: 'Ana', cedula: '12345', fechaNacimiento: '1990-05-01', genero: 'F' as const };
const httpError = (status: number, code = '', message = '') =>
  throwError(() => new HttpErrorResponse({ status, error: { code, message } }));

describe('Perfil', () => {
  const api = { get: vi.fn(), create: vi.fn(), update: vi.fn() };

  const crear = async () => {
    TestBed.configureTestingModule({
      imports: [Perfil],
      providers: [
        provideRouter([]),
        { provide: ProfileApi, useValue: api },
        { provide: AuthService, useValue: { email: () => 'a@b.co', logout: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(Perfil);
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  };

  const escribir = (el: HTMLElement, id: string, valor: string) => {
    const input = el.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
    input.value = valor;
    input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
  };

  const enviar = async (fixture: { whenStable(): Promise<unknown>; detectChanges(): void }, el: HTMLElement) => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(() => Object.values(api).forEach((f) => f.mockReset()));

  it('si GET da 404 muestra el formulario de primer registro y guarda con POST', async () => {
    api.get.mockReturnValue(httpError(404, 'NO_ENCONTRADO', 'no existe'));
    api.create.mockReturnValue(of(PERFIL));
    const { fixture, el } = await crear();

    expect(el.querySelector('h1')?.textContent).toContain('Completa tu perfil');
    escribir(el, 'nombre', 'Ana');
    escribir(el, 'cedula', '12345');
    escribir(el, 'fechaNacimiento', '1990-05-01');
    escribir(el, 'genero', 'F');
    await enviar(fixture, el);

    expect(api.create).toHaveBeenCalledWith({ nombre: 'Ana', cedula: '12345', fechaNacimiento: '1990-05-01', genero: 'F' });
    expect(api.update).not.toHaveBeenCalled();
    expect(el.querySelector('h1')?.textContent).toContain('Mi perfil');
    expect(el.querySelector('[role=status]')?.textContent).toContain('guardado');
  });

  it('si el perfil existe muestra los datos y guarda los cambios con PUT', async () => {
    api.get.mockReturnValue(of(PERFIL));
    api.update.mockReturnValue(of({ ...PERFIL, nombre: 'Ana María' }));
    const { fixture, el } = await crear();

    expect(el.querySelector('h1')?.textContent).toContain('Mi perfil');
    expect(el.textContent).toContain('a@b.co');
    expect(el.querySelector<HTMLInputElement>('#cedula')!.value).toBe('12345');
    escribir(el, 'nombre', 'Ana María');
    await enviar(fixture, el);

    expect(api.update).toHaveBeenCalledWith({ nombre: 'Ana María', cedula: '12345', fechaNacimiento: '1990-05-01', genero: 'F' });
    expect(api.create).not.toHaveBeenCalled();
  });

  it('no envía si los datos no cumplen la validación', async () => {
    api.get.mockReturnValue(httpError(404));
    const { fixture, el } = await crear();

    escribir(el, 'nombre', 'Ana');
    escribir(el, 'cedula', '12a4');
    escribir(el, 'fechaNacimiento', '1899-12-31');
    escribir(el, 'genero', 'F');
    await enviar(fixture, el);

    expect(api.create).not.toHaveBeenCalled();
    expect(el.querySelector('#cedula-error')).toBeTruthy();
    expect(el.querySelector('#fechaNacimiento-error')).toBeTruthy();
  });

  it('rechaza una fecha futura', async () => {
    api.get.mockReturnValue(httpError(404));
    const { fixture, el } = await crear();
    const manana = new Date(Date.now() + 2 * 86400000).toLocaleDateString('en-CA');

    escribir(el, 'nombre', 'Ana');
    escribir(el, 'cedula', '12345');
    escribir(el, 'fechaNacimiento', manana);
    escribir(el, 'genero', 'F');
    await enviar(fixture, el);

    expect(api.create).not.toHaveBeenCalled();
    expect(el.querySelector('#fechaNacimiento-error')).toBeTruthy();
  });

  it('muestra el message del backend ante un 400', async () => {
    api.get.mockReturnValue(of(PERFIL));
    api.update.mockReturnValue(httpError(400, 'VALIDACION', 'cedula debe tener de 5 a 15 dígitos'));
    const { fixture, el } = await crear();

    await enviar(fixture, el);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('cedula debe tener de 5 a 15 dígitos');
  });

  it('muestra un error si no se puede cargar el perfil', async () => {
    api.get.mockReturnValue(httpError(500, 'ERROR_INTERNO', 'detalle interno'));
    const { el } = await crear();

    expect(el.querySelector('[role=alert]')?.textContent).toContain('No se pudo cargar tu perfil');
    expect(el.querySelector('form')).toBeNull();
  });
});
