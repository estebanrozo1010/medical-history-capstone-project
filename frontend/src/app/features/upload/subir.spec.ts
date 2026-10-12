import { HttpErrorResponse, HttpEventType, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { FilesApi } from '../../core/api/files.api';
import { Subir } from './subir';

const SUBIDA = { fileId: 'f1', url: 'https://s3/f1', headers: { 'content-type': 'application/pdf' }, expiresIn: 600 };

describe('Subir', () => {
  const api = { pedirSubida: vi.fn(), subirAS3: vi.fn(), listar: vi.fn() };

  const crear = async () => {
    TestBed.configureTestingModule({
      imports: [Subir],
      providers: [provideRouter([]), { provide: FilesApi, useValue: api }],
    });
    const fixture = TestBed.createComponent(Subir);
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  };

  const llenar = (el: HTMLElement, carpeta: string, archivo?: File) => {
    const select = el.querySelector<HTMLSelectElement>('#carpeta')!;
    select.value = carpeta;
    select.dispatchEvent(new Event('change'));
    if (archivo) {
      const input = el.querySelector<HTMLInputElement>('#archivo')!;
      Object.defineProperty(input, 'files', { value: [archivo] });
      input.dispatchEvent(new Event('change'));
    }
  };

  const enviar = async (fixture: { whenStable(): Promise<unknown>; detectChanges(): void }, el: HTMLElement) => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const pdf = () => new File(['%PDF-1.4'], 'examen.pdf', { type: 'application/pdf' });

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    api.listar.mockReturnValue(of([]));
  });
  afterEach(() => vi.useRealTimers());

  it('pide la URL, sube a S3 mostrando el progreso y termina en estado Pendiente', async () => {
    const s3 = new Subject<unknown>();
    api.pedirSubida.mockReturnValue(of(SUBIDA));
    api.subirAS3.mockReturnValue(s3);
    const { fixture, el } = await crear();
    const archivo = pdf();

    llenar(el, 'laboratorio', archivo);
    await enviar(fixture, el);

    expect(api.pedirSubida).toHaveBeenCalledWith('laboratorio', archivo);
    expect(api.subirAS3).toHaveBeenCalledWith(SUBIDA, archivo);
    s3.next({ type: HttpEventType.UploadProgress, loaded: 40, total: 100 });
    fixture.detectChanges();
    const barra = el.querySelector<HTMLProgressElement>('progress')!;
    expect(barra.value).toBe(40);
    expect(el.querySelector('label[for=progreso]')?.textContent).toContain('40 %');
    expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);

    s3.next(new HttpResponse({ status: 200 }));
    s3.complete();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector<HTMLProgressElement>('progress')!.value).toBe(100);
    const ok = el.querySelector('[role=status]')!;
    expect(ok.textContent).toContain('examen.pdf');
    expect(ok.textContent).toContain('Pendiente');
    expect(ok.querySelector('a')?.getAttribute('href')).toBe('/carpetas/laboratorio');
  });

  it('valida carpeta, tipo y tamaño en el cliente sin llamar a la API', async () => {
    const { fixture, el } = await crear();

    await enviar(fixture, el);
    expect(el.querySelector('#carpeta-error')).toBeTruthy();
    expect(el.querySelector('#archivo-error')?.textContent).toContain('Elige un archivo');

    llenar(el, 'laboratorio', new File(['MZ'], 'virus.exe', { type: 'application/x-msdownload' }));
    await enviar(fixture, el);
    expect(el.querySelector('#archivo-error')?.textContent).toContain('PDF, JPG, PNG o MP4');
    expect(api.pedirSubida).not.toHaveBeenCalled();
  });

  it('muestra el message del backend ante un 400', async () => {
    api.pedirSubida.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'VALIDACION', message: 'size debe ser un entero entre 1 byte y 2 GB' } })),
    );
    const { fixture, el } = await crear();

    llenar(el, 'laboratorio', pdf());
    await enviar(fixture, el);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('size debe ser un entero');
    expect(api.subirAS3).not.toHaveBeenCalled();
  });

  it('si falla el PUT a S3 muestra un error y deja reintentar', async () => {
    api.pedirSubida.mockReturnValue(of(SUBIDA));
    api.subirAS3.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403, error: '<Error>SignatureDoesNotMatch</Error>' })));
    const { fixture, el } = await crear();

    llenar(el, 'laboratorio', pdf());
    await enviar(fixture, el);

    expect(el.querySelector('[role=alert]')?.textContent).toContain('No se pudo subir el archivo');
    expect(el.querySelector('[role=status]')).toBeNull();
    expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
  });

  describe('tras subir, consulta el estado cada 3 s', () => {
    const archivo = (status: string) => ({
      fileId: 'f1', carpeta: 'laboratorio', nombre: 'examen.pdf', contentType: 'application/pdf', size: 8, status, createdAt: 1,
    });

    const subirYEsperar = async (...estados: string[]) => {
      // Solo setInterval (lo usa timer de RxJS): whenStable de Angular necesita el setTimeout real
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      api.pedirSubida.mockReturnValue(of(SUBIDA));
      api.subirAS3.mockReturnValue(of(new HttpResponse({ status: 200 })));
      // Otro archivo de la carpeta ya limpio: solo cuenta el que se acaba de subir
      estados.forEach((s) => api.listar.mockReturnValueOnce(of([{ ...archivo('CLEAN'), fileId: 'otro' }, archivo(s)])));
      const { fixture, el } = await crear();
      llenar(el, 'laboratorio', pdf());
      await enviar(fixture, el);
      return { fixture, el };
    };

    const avanzar = async (fixture: { detectChanges(): void }, ms: number) => {
      await vi.advanceTimersByTimeAsync(ms);
      fixture.detectChanges();
    };

    const estado = (el: HTMLElement) => el.querySelector('[role=status]')?.textContent ?? '';

    it('pasa de Pendiente a Limpio sin recargar', async () => {
      const { fixture, el } = await subirYEsperar('PENDING', 'CLEAN');
      expect(estado(el)).toContain('Pendiente');
      expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);

      await avanzar(fixture, 3000);
      expect(api.listar).toHaveBeenCalledWith('laboratorio');
      expect(estado(el)).toContain('Pendiente');

      await avanzar(fixture, 3000);
      expect(estado(el)).toContain('Limpio');
      expect(estado(el)).not.toContain('fallida');
      expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
      await avanzar(fixture, 30_000);
      expect(api.listar).toHaveBeenCalledTimes(2);
    });

    it('si pasa a Rechazado muestra "subida fallida, vuelve a intentar" con el motivo', async () => {
      const { fixture, el } = await subirYEsperar('REJECTED');

      await avanzar(fixture, 3000);

      expect(estado(el)).toContain('Rechazado');
      expect(estado(el)).toContain('Subida fallida, vuelve a intentar');
      expect(estado(el)).toContain('no corresponde al tipo');
      expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
    });

    it('UPLOAD_EXPIRED y SCAN_FAILED también son subida fallida', async () => {
      for (const s of ['UPLOAD_EXPIRED', 'SCAN_FAILED']) {
        TestBed.resetTestingModule();
        const { fixture, el } = await subirYEsperar(s);
        await avanzar(fixture, 3000);
        expect(estado(el)).toContain('Subida fallida, vuelve a intentar');
      }
    });

    it('se agota a los 2 minutos sin dar la subida por fallida', async () => {
      api.listar.mockReturnValue(of([{ ...archivo('PENDING'), fileId: SUBIDA.fileId }]));
      const { fixture, el } = await subirYEsperar();

      await avanzar(fixture, 120_000);

      expect(api.listar).toHaveBeenCalledTimes(40);
      expect(estado(el)).toContain('está tardando');
      expect(estado(el)).not.toContain('fallida');
      expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
      await avanzar(fixture, 30_000);
      expect(api.listar).toHaveBeenCalledTimes(40);
    });

    it('un error de red al consultar no corta la espera', async () => {
      api.listar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
      const { fixture, el } = await subirYEsperar('CLEAN');
      // el mockReturnValueOnce del error se consume primero; luego CLEAN
      await avanzar(fixture, 6000);

      expect(estado(el)).toContain('Limpio');
    });
  });
});
