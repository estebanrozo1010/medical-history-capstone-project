import { HttpErrorResponse } from '@angular/common/http';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
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
  const api = { listar: vi.fn() };

  const crear = async <T>(componente: Type<T>, nombre = '') => {
    TestBed.configureTestingModule({
      imports: [componente],
      providers: [
        provideRouter([]),
        { provide: FilesApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ nombre }) } } },
      ],
    });
    const fixture = TestBed.createComponent(componente);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(() => api.listar.mockReset());

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
});
