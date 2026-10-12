import { HttpErrorResponse, HttpEventType, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { FilesApi } from '../../core/api/files.api';
import { Subir } from './subir';

const SUBIDA = { fileId: 'f1', url: 'https://s3/f1', headers: { 'content-type': 'application/pdf' }, expiresIn: 600 };

describe('Subir', () => {
  const api = { pedirSubida: vi.fn(), subirAS3: vi.fn() };

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

  beforeEach(() => Object.values(api).forEach((f) => f.mockReset()));

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
});
