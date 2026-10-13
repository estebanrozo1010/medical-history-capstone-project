import { HttpErrorResponse } from '@angular/common/http';
import { Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Archivo, FilesApi } from '../../core/api/files.api';
import { Link, LinksApi } from '../../core/api/links.api';
import { Carpeta } from '../folders/carpeta';
import { Links } from './links';
import { Seleccion } from './seleccion';

const AHORA = Math.floor(Date.now() / 1000);
const archivo = (fileId: string, status: Archivo['status'] = 'CLEAN', carpeta = 'laboratorio'): Archivo => ({
  fileId, carpeta, nombre: `${fileId}.pdf`, contentType: 'application/pdf', size: 1000, status, createdAt: AHORA,
});
const link = (token: string, activo = true, nombres = ['b.pdf', 'a.pdf']): Link => ({
  token, activo, expiresAt: AHORA + 3600, archivos: nombres.map((n) => ({ fileId: `id-${n}`, nombre: n })),
});
const errorHttp = (status: number, message?: string) =>
  throwError(() => new HttpErrorResponse({ status, error: message ? { code: 'X', message } : null }));

describe('Compartir', () => {
  const files = { listar: vi.fn(), urlDescarga: vi.fn(), eliminar: vi.fn() };
  const links = { crear: vi.fn(), listar: vi.fn(), cambiarEstado: vi.fn() };
  const email = signal<string | null>('a@x.co');
  let fixture: ComponentFixture<unknown>;
  let seleccion: Seleccion;

  beforeEach(() => {
    vi.resetAllMocks();
    email.set('a@x.co');
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: FilesApi, useValue: files },
        { provide: LinksApi, useValue: links },
        { provide: AuthService, useValue: { email } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ nombre: 'laboratorio' }) } } },
      ],
    });
    seleccion = TestBed.inject(Seleccion);
  });

  const crear = async <T>(componente: Type<T>) => {
    fixture = TestBed.createComponent(componente);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };
  const listo = async () => {
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const boton = (el: HTMLElement, texto: string) => {
    const b = [...el.querySelectorAll('button')].find((x) => x.textContent!.trim() === texto);
    expect(b, `botón ${texto}`).toBeTruthy();
    return b!;
  };
  const casilla = (el: HTMLElement, etiqueta: string) =>
    el.querySelector<HTMLInputElement>(`input[type=checkbox][aria-label="${etiqueta}"]`);
  const marcar = async (input: HTMLInputElement) => {
    input.click();
    await listo();
  };
  const estados = (el: HTMLElement) => [...el.querySelectorAll('[role=status]')].map((s) => s.textContent!.trim()).join(' | ');

  describe('selección en /carpetas/:nombre', () => {
    it('solo los CLEAN tienen casilla; marcar uno muestra el bloque Compartir', async () => {
      files.listar.mockReturnValue(of([archivo('limpio'), archivo('pend', 'PENDING'), archivo('rech', 'REJECTED')]));
      const el = await crear(Carpeta);

      expect(casilla(el, 'Seleccionar limpio.pdf')).toBeTruthy();
      expect(casilla(el, 'Seleccionar pend.pdf')).toBeNull();
      expect(casilla(el, 'Seleccionar rech.pdf')).toBeNull();
      expect(el.textContent).not.toContain('Crear link');

      await marcar(casilla(el, 'Seleccionar limpio.pdf')!);

      expect(seleccion.archivos()).toEqual([{ fileId: 'limpio', nombre: 'limpio.pdf' }]);
      expect(el.textContent).toContain('1 archivo seleccionado');
      boton(el, 'Crear link');
    });

    it('la selección se conserva entre carpetas: llega marcada y suma los de otra carpeta', async () => {
      seleccion.alternar({ fileId: 'eco', nombre: 'eco.pdf' });
      files.listar.mockReturnValue(of([archivo('l1'), archivo('l2')]));
      const el = await crear(Carpeta);

      await marcar(casilla(el, 'Seleccionar l1.pdf')!);

      expect(el.textContent).toContain('2 archivos seleccionados');
      expect(seleccion.archivos().map((a) => a.fileId)).toEqual(['eco', 'l1']);
    });

    it('"Seleccionar todos los limpios" marca y desmarca solo los de esta carpeta', async () => {
      seleccion.alternar({ fileId: 'eco', nombre: 'eco.pdf' });
      files.listar.mockReturnValue(of([archivo('l1'), archivo('l2'), archivo('pend', 'PENDING')]));
      const el = await crear(Carpeta);
      const todos = el.querySelector<HTMLInputElement>('#todos-limpios')!;
      expect(el.querySelector('label[for=todos-limpios]')?.textContent).toContain('Seleccionar todos los limpios');

      await marcar(todos);
      expect(seleccion.archivos().map((a) => a.fileId)).toEqual(['eco', 'l1', 'l2']);
      expect(todos.checked).toBe(true);

      await marcar(todos);
      expect(seleccion.archivos().map((a) => a.fileId)).toEqual(['eco']);
    });

    it('con 50 elegidos, las casillas sin marcar se deshabilitan y "todos" no pasa de 50', async () => {
      for (let i = 0; i < 49; i++) seleccion.alternar({ fileId: `x${i}`, nombre: `x${i}.pdf` });
      files.listar.mockReturnValue(of([archivo('l1'), archivo('l2')]));
      const el = await crear(Carpeta);

      await marcar(el.querySelector<HTMLInputElement>('#todos-limpios')!);

      expect(seleccion.archivos()).toHaveLength(50);
      expect(casilla(el, 'Seleccionar l1.pdf')!.checked).toBe(true);
      expect(casilla(el, 'Seleccionar l2.pdf')!.disabled).toBe(true);
      expect(el.textContent).toContain('máximo 50');
    });

    it('al eliminar un archivo elegido se quita de la selección', async () => {
      files.listar.mockReturnValue(of([archivo('l1'), archivo('l2')]));
      files.eliminar.mockReturnValue(of(null));
      const el = await crear(Carpeta);
      await marcar(casilla(el, 'Seleccionar l1.pdf')!);

      boton(el, 'Eliminar').click();
      await listo();
      boton(el, 'Confirmar eliminar').click();
      await listo();

      expect(seleccion.archivos()).toEqual([]);
    });
  });

  describe('crear el link', () => {
    const preparar = async () => {
      seleccion.alternar({ fileId: 'eco', nombre: 'eco.pdf' });
      files.listar.mockReturnValue(of([archivo('l1')]));
      const el = await crear(Carpeta);
      await marcar(casilla(el, 'Seleccionar l1.pdf')!);
      return el;
    };

    it('las duraciones son 15 min, 1 h, 2 h y 24 h, con 1 h por defecto', async () => {
      const el = await preparar();
      const select = el.querySelector<HTMLSelectElement>('#duracion')!;
      expect(el.querySelector('label[for=duracion]')).toBeTruthy();
      expect([...select.options].map((o) => `${o.value}:${o.textContent!.trim()}`)).toEqual(['15:15 minutos', '60:1 hora', '120:2 horas', '1440:24 horas']);
      expect(select.value).toBe('60');
    });

    it('crea el link con los archivos de varias carpetas, muestra la URL, limpia la selección y lleva el foco', async () => {
      links.crear.mockReturnValue(of({ token: 'tok123', url: 'http://localhost:4200/compartido/tok123', expiresAt: AHORA + 7200 }));
      const el = await preparar();
      const select = el.querySelector<HTMLSelectElement>('#duracion')!;
      select.value = '120';
      select.dispatchEvent(new Event('change'));
      await listo();

      boton(el, 'Crear link').click();
      await listo();

      expect(links.crear).toHaveBeenCalledWith([{ fileId: 'eco', nombre: 'eco.pdf' }, { fileId: 'l1', nombre: 'l1.pdf' }], 120);
      expect(el.querySelector<HTMLInputElement>('#url-creada')!.value).toBe('http://localhost:4200/compartido/tok123');
      expect(el.querySelector<HTMLInputElement>('#url-creada')!.readOnly).toBe(true);
      expect(estados(el)).toContain('Link creado');
      expect(seleccion.archivos()).toEqual([]);
      expect(document.activeElement?.id).toBe('link-creado');
      expect(el.querySelector('a[href="/links"]')).toBeTruthy();
    });

    it.each([
      [400, 'Máximo 50 archivos por link', 'Máximo 50 archivos por link'],
      [404, undefined, 'ya no existe'],
      [409, 'Solo se pueden compartir archivos limpios (CLEAN)', 'Solo se pueden compartir archivos limpios'],
      [0, undefined, 'conexión'],
    ])('un %s al crear se muestra con role=alert y la selección se conserva', async (status, message, esperado) => {
      links.crear.mockReturnValue(errorHttp(status, message));
      const el = await preparar();

      boton(el, 'Crear link').click();
      await listo();

      expect(el.querySelector('[role=alert]')?.textContent).toContain(esperado);
      expect(seleccion.archivos()).toHaveLength(2);
      expect(boton(el, 'Crear link').disabled).toBe(false);
    });

    it('"Quitar selección" vacía la selección', async () => {
      const el = await preparar();
      boton(el, 'Quitar selección').click();
      await listo();
      expect(seleccion.archivos()).toEqual([]);
      expect(el.textContent).not.toContain('Crear link');
    });

    it('al cambiar de usuario la selección se vacía', async () => {
      seleccion.alternar({ fileId: 'eco', nombre: 'eco.pdf' });
      email.set('b@x.co');
      expect(seleccion.archivos()).toEqual([]);
    });
  });

  describe('copiar la URL', () => {
    const conLink = async () => {
      links.crear.mockReturnValue(of({ token: 't', url: 'http://localhost:4200/compartido/t', expiresAt: AHORA + 3600 }));
      seleccion.alternar({ fileId: 'eco', nombre: 'eco.pdf' });
      files.listar.mockReturnValue(of([]));
      const el = await crear(Carpeta);
      boton(el, 'Crear link').click();
      await listo();
      return el;
    };
    const clipboard = (valor: unknown) => Object.defineProperty(navigator, 'clipboard', { value: valor, configurable: true });
    const execCommand = (valor: () => boolean) => Object.defineProperty(document, 'execCommand', { value: valor, configurable: true });

    afterEach(() => {
      clipboard(undefined);
      execCommand(() => false);
    });

    it('con la Clipboard API copia la URL', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      clipboard({ writeText });
      const el = await conLink();

      boton(el, 'Copiar').click();
      await listo();

      expect(writeText).toHaveBeenCalledWith('http://localhost:4200/compartido/t');
      expect(estados(el)).toContain('Enlace copiado');
    });

    it('sin Clipboard API (sitio HTTP) selecciona el texto y usa execCommand', async () => {
      clipboard(undefined);
      const exec = vi.fn(() => true);
      execCommand(exec);
      const el = await conLink();
      const campo = el.querySelector<HTMLInputElement>('#url-creada')!;

      boton(el, 'Copiar').click();
      await listo();

      expect(exec).toHaveBeenCalledWith('copy');
      expect(campo.selectionEnd! - campo.selectionStart!).toBe(campo.value.length);
      expect(estados(el)).toContain('Enlace copiado');
    });

    it('si todo falla deja el texto seleccionado y pide copiarlo con Ctrl+C', async () => {
      clipboard({ writeText: vi.fn().mockRejectedValue(new Error('NotAllowed')) });
      execCommand(() => { throw new Error('no soportado'); });
      const el = await conLink();
      const campo = el.querySelector<HTMLInputElement>('#url-creada')!;

      boton(el, 'Copiar').click();
      await listo();

      expect(campo.selectionEnd! - campo.selectionStart!).toBe(campo.value.length);
      expect(estados(el)).toContain('Ctrl+C');
    });
  });

  describe('/links', () => {
    it('lista los links con sus archivos ordenados por nombre, la expiración, el switch y la URL', async () => {
      links.listar.mockReturnValue(of([link('t1'), link('t2', false, ['z.pdf'])]));
      const el = await crear(Links);

      const items = el.querySelectorAll('.lista li');
      expect(items).toHaveLength(2);
      expect(items[0].textContent).toContain('a.pdf, b.pdf');
      expect(items[0].textContent).toContain('2 archivos');
      expect(items[0].textContent).toContain('Vence');
      const switches = el.querySelectorAll<HTMLInputElement>('input[role=switch]');
      expect([...switches].map((s) => s.checked)).toEqual([true, false]);
      expect(switches[0].getAttribute('aria-label')).toBe('Link activo: a.pdf, b.pdf');
      expect(el.querySelector<HTMLInputElement>('#url-t1')!.value).toBe(`${location.origin}/compartido/t1`);
      expect(el.querySelector('label[for=url-t1]')!.textContent).toBe('Enlace de a.pdf, b.pdf');
      expect(items[0].querySelector('button[aria-label="Copiar enlace de a.pdf, b.pdf"]')).toBeTruthy();
    });

    it('sin links muestra cómo crear uno; si falla el GET, role=alert', async () => {
      links.listar.mockReturnValue(of([]));
      expect((await crear(Links)).textContent).toContain('No tienes links vigentes');

      links.listar.mockReturnValue(errorHttp(500));
      expect((await crear(Links)).querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar');
    });

    it('el switch apaga el link con PATCH, la lista se actualiza y se anuncia', async () => {
      links.listar.mockReturnValue(of([link('t1')]));
      links.cambiarEstado.mockReturnValue(of({ token: 't1', activo: false, expiresAt: AHORA + 3600 }));
      const el = await crear(Links);
      const sw = el.querySelector<HTMLInputElement>('input[role=switch]')!;

      await marcar(sw);

      expect(links.cambiarEstado).toHaveBeenCalledWith('t1', false);
      expect(el.querySelector<HTMLInputElement>('input[role=switch]')!.checked).toBe(false);
      expect(estados(el)).toContain('Se desactivó');
    });

    it('un 404 al cambiar el switch quita el link de la lista con role=alert', async () => {
      links.listar.mockReturnValue(of([link('t1'), link('t2')]));
      links.cambiarEstado.mockReturnValue(errorHttp(404));
      const el = await crear(Links);

      await marcar(el.querySelector<HTMLInputElement>('input[role=switch]')!);

      expect(el.querySelectorAll('.lista li')).toHaveLength(1);
      expect(el.querySelector('[role=alert]')?.textContent).toContain('ya no existe o venció');
      expect(document.activeElement?.id).toBe('titulo');
    });

    it('un error de red deja el switch como estaba, con role=alert', async () => {
      links.listar.mockReturnValue(of([link('t1')]));
      links.cambiarEstado.mockReturnValue(errorHttp(0));
      const el = await crear(Links);
      const sw = el.querySelector<HTMLInputElement>('input[role=switch]')!;

      await marcar(sw);

      expect(sw.checked).toBe(true);
      expect(sw.disabled).toBe(false);
      expect(el.querySelector('[role=alert]')?.textContent).toContain('conexión');
    });
  });
});
