import { HttpErrorResponse } from '@angular/common/http';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Archivo, FilesApi } from '../../core/api/files.api';
import { Carpeta } from './carpeta';
import { Carpetas } from './carpetas';

const AHORA = Math.floor(Date.now() / 1000);
const archivo = (fileId: string, carpeta: string, status: Archivo['status'], size = 1000): Archivo => ({
  fileId, carpeta, nombre: `${fileId}.pdf`, contentType: 'application/pdf', size, status, createdAt: AHORA,
});

describe('Carpetas y Carpeta', () => {
  const api = { listar: vi.fn(), urlDescarga: vi.fn(), eliminar: vi.fn() };
  let fixture: ComponentFixture<unknown>;

  const crear = async <T>(componente: Type<T>, nombre = '') => {
    TestBed.configureTestingModule({
      imports: [componente],
      providers: [
        provideRouter([]),
        { provide: FilesApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ nombre }) } } },
      ],
    });
    fixture = TestBed.createComponent(componente);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => vi.resetAllMocks());

  const clic = async (el: HTMLElement, texto: string, nombre?: string) => {
    const boton = [...el.querySelectorAll('button')].find((b) => b.textContent!.trim() === texto
      && (!nombre || b.closest('li')!.textContent!.includes(nombre)));
    expect(boton, `botón ${texto} ${nombre ?? ''}`).toBeTruthy();
    boton!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const botones = (el: HTMLElement, texto: string) => [...el.querySelectorAll('button')].filter((b) => b.textContent!.trim() === texto);
  const error409 = (message: string) => throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'CONFLICTO', message } }));

  it('/carpetas lista las 11 especialidades con su total, con un solo GET /files', async () => {
    api.listar.mockReturnValue(of([archivo('a', 'laboratorio', 'PENDING'), archivo('b', 'laboratorio', 'CLEAN'),
      archivo('c', 'cardiologia', 'CLEAN')]));
    const el = await crear(Carpetas);

    expect(api.listar).toHaveBeenCalledTimes(1);
    expect(api.listar).toHaveBeenCalledWith();
    const items = [...el.querySelectorAll('li')].map((li) => `${li.querySelector('a')!.textContent} ${li.querySelector('span')!.textContent}`);
    expect(items).toHaveLength(11);
    expect(items).toContain('Laboratorio 2 archivos');
    expect(items).toContain('Cardiología 1 archivo');
    expect(items).toContain('Pediatría 0 archivos');
    expect(el.querySelector('a[href="/carpetas/medicina-general"]')).toBeTruthy();
  });

  it('/carpetas muestra un error si falla el GET', async () => {
    api.listar.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const el = await crear(Carpetas);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar');
  });

  it('/carpetas/:nombre lista los archivos con tamaño, fecha y estado en español', async () => {
    api.listar.mockReturnValue(of([archivo('pend', 'laboratorio', 'PENDING', 1536), archivo('limpio', 'laboratorio', 'CLEAN'),
      archivo('inf', 'laboratorio', 'INFECTED'), archivo('rech', 'laboratorio', 'REJECTED'),
      archivo('venc', 'laboratorio', 'UPLOAD_EXPIRED')]));
    const el = await crear(Carpeta, 'laboratorio');

    expect(api.listar).toHaveBeenCalledWith('laboratorio');
    expect(el.querySelector('h1')?.textContent).toContain('Laboratorio');
    const texto = el.textContent!;
    for (const estado of ['Pendiente', 'Limpio', 'Infectado', 'Rechazado', 'Subida vencida']) expect(texto).toContain(estado);
    expect(texto).toContain('pend.pdf');
    expect(texto).toContain('1,5 KB');
    expect(texto).toContain(String(new Date(AHORA * 1000).getFullYear()));
  });

  it('/carpetas/:nombre vacía o inexistente', async () => {
    api.listar.mockReturnValue(of([]));
    expect((await crear(Carpeta, 'pediatria')).textContent).toContain('no tiene archivos');
    TestBed.resetTestingModule();
    const el = await crear(Carpeta, 'secreta');
    expect(el.querySelector('h1')?.textContent).toContain('Carpeta no encontrada');
    expect(api.listar).toHaveBeenCalledTimes(1);
  });

  describe('descargar y eliminar en /carpetas/:nombre', () => {
    const lista = () => of([archivo('limpio', 'laboratorio', 'CLEAN'), archivo('pend', 'laboratorio', 'PENDING'),
      archivo('rech', 'laboratorio', 'REJECTED')]);

    it('Descargar solo aparece en archivos CLEAN y abre la URL firmada', async () => {
      api.listar.mockReturnValue(lista());
      api.urlDescarga.mockReturnValue(of({ url: 'https://s3/firmada', expiresIn: 300 }));
      const abrir = vi.spyOn(Carpeta.prototype as unknown as { abrir: (u: string) => void }, 'abrir').mockImplementation(() => {});
      const el = await crear(Carpeta, 'laboratorio');

      expect(botones(el, 'Descargar')).toHaveLength(1);
      expect(botones(el, 'Descargar')[0].getAttribute('aria-label')).toBe('Descargar limpio.pdf');
      expect(botones(el, 'Eliminar')).toHaveLength(3);
      await clic(el, 'Descargar', 'limpio.pdf');

      expect(api.urlDescarga).toHaveBeenCalledWith('limpio');
      expect(abrir).toHaveBeenCalledWith('https://s3/firmada');
    });

    it('Eliminar pide confirmación con foco en Cancelar y Cancelar devuelve el foco sin borrar', async () => {
      api.listar.mockReturnValue(lista());
      const el = await crear(Carpeta, 'laboratorio');

      await clic(el, 'Eliminar', 'pend.pdf');

      expect(api.eliminar).not.toHaveBeenCalled();
      expect(el.textContent).toContain('No se puede deshacer');
      expect(document.activeElement?.textContent?.trim()).toBe('Cancelar');
      await clic(el, 'Cancelar', 'pend.pdf');
      expect(document.activeElement?.getAttribute('aria-label')).toBe('Eliminar pend.pdf');
      expect(botones(el, 'Confirmar eliminar')).toHaveLength(0);
      expect(api.eliminar).not.toHaveBeenCalled();
    });

    it('Confirmar eliminar borra, quita el archivo de la lista y lo anuncia con role=status', async () => {
      api.listar.mockReturnValue(lista());
      api.eliminar.mockReturnValue(of(null));
      const el = await crear(Carpeta, 'laboratorio');

      await clic(el, 'Eliminar', 'pend.pdf');
      await clic(el, 'Confirmar eliminar', 'pend.pdf');

      expect(api.eliminar).toHaveBeenCalledWith('pend');
      expect(el.querySelector('.lista')!.textContent).not.toContain('pend.pdf');
      expect(el.querySelectorAll('li')).toHaveLength(2);
      expect(el.querySelector('[role=status]')?.textContent).toContain('Se eliminó «pend.pdf»');
      expect(document.activeElement?.tagName).toBe('H1');
    });

    it('un 409 al eliminar se muestra con role=alert y el archivo sigue en la lista, listo para reintentar', async () => {
      api.listar.mockReturnValue(lista());
      api.eliminar.mockReturnValue(error409('La copia de respaldo sigue en curso; reintenta en unos segundos'));
      const el = await crear(Carpeta, 'laboratorio');

      await clic(el, 'Eliminar', 'limpio.pdf');
      await clic(el, 'Confirmar eliminar', 'limpio.pdf');

      expect(el.querySelector('[role=alert]')?.textContent).toContain('reintenta en unos segundos');
      expect(el.querySelector('.lista')!.textContent).toContain('limpio.pdf');
      // La confirmación sigue abierta: reintentar es un clic
      expect(botones(el, 'Confirmar eliminar')).toHaveLength(1);
      expect(botones(el, 'Confirmar eliminar')[0].disabled).toBe(false);
    });

    it('404 y errores de red al descargar se muestran con role=alert', async () => {
      api.listar.mockReturnValue(lista());
      const el = await crear(Carpeta, 'laboratorio');

      api.urlDescarga.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404, error: { code: 'NO_ENCONTRADO' } })));
      await clic(el, 'Descargar', 'limpio.pdf');
      expect(el.querySelector('[role=alert]')?.textContent).toContain('ya no existe');

      api.urlDescarga.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      await clic(el, 'Descargar', 'limpio.pdf');
      expect(el.querySelector('[role=alert]')?.textContent).toContain('conexión');
    });
  });
});
