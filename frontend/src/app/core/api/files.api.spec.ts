import { HttpEventType, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from '../auth/auth.interceptor';
import { Config } from '../config';
import { CARPETAS, FilesApi, TAMANO_MAXIMO, validarArchivo } from './files.api';

const API = 'https://api.test/v1';
const S3 = 'https://mh-docs.s3.amazonaws.com/sub/f1?X-Amz-Signature=abc';
const turno = () => new Promise((r) => setTimeout(r));
const archivo = (nombre: string, tipo: string, size = 10) =>
  Object.defineProperty(new File(['x'], nombre, { type: tipo }), 'size', { value: size });

describe('FilesApi', () => {
  let api: FilesApi;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        // Con el interceptor real: así se comprueba que el PUT a S3 no lleva el token
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { idToken: async () => 'tok', refresh: async () => 'tok', logout: () => {} } },
      ],
    });
    TestBed.inject(Config).value = { apiUrl: API, userPoolId: '', clientId: '', region: '' };
    api = TestBed.inject(FilesApi);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('listar sin carpeta hace GET {apiUrl}/files con el token', async () => {
    let lista: unknown;
    api.listar().subscribe((l) => (lista = l));
    await turno();
    const req = backend.expectOne(`${API}/files`);
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
    req.flush([]);
    expect(lista).toEqual([]);
  });

  it('listar con carpeta manda ?carpeta=', async () => {
    api.listar('cardiologia').subscribe();
    await turno();
    backend.expectOne(`${API}/files?carpeta=cardiologia`).flush([]);
  });

  it('pedirSubida hace POST {apiUrl}/files/upload-url con carpeta, nombre, tipo y tamaño', async () => {
    api.pedirSubida('laboratorio', archivo('examen.pdf', 'application/pdf', 1234)).subscribe();
    await turno();
    const req = backend.expectOne(`${API}/files/upload-url`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ carpeta: 'laboratorio', nombre: 'examen.pdf', contentType: 'application/pdf', size: 1234 });
    req.flush({ fileId: 'f1', url: S3, headers: {}, expiresIn: 600 }, { status: 201, statusText: 'Created' });
  });

  it('subirAS3 hace PUT a la URL firmada con sus headers, sin Authorization y con progreso', async () => {
    const f = archivo('examen.pdf', 'application/pdf');
    const tipos: HttpEventType[] = [];
    api.subirAS3({ fileId: 'f1', url: S3, headers: { 'content-type': 'application/pdf' }, expiresIn: 600 }, f)
      .subscribe((e) => tipos.push(e.type));
    await turno();
    const req = backend.expectOne(S3);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe(f);
    expect(req.request.reportProgress).toBe(true);
    expect(req.request.headers.get('content-type')).toBe('application/pdf');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.event({ type: HttpEventType.UploadProgress, loaded: 5, total: 10 });
    req.flush(null);
    expect(tipos).toContain(HttpEventType.UploadProgress);
  });

  it('urlDescarga hace GET {apiUrl}/files/{fileId}/download-url con el token', async () => {
    let r: unknown;
    api.urlDescarga('f1').subscribe((x) => (r = x));
    await turno();
    const req = backend.expectOne(`${API}/files/f1/download-url`);
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
    req.flush({ url: S3, expiresIn: 300 });
    expect(r).toEqual({ url: S3, expiresIn: 300 });
  });

  it('eliminar hace DELETE {apiUrl}/files/{fileId} con el token', async () => {
    api.eliminar('f1').subscribe();
    await turno();
    const req = backend.expectOne(`${API}/files/f1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tok');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});

describe('validarArchivo', () => {
  it('acepta PDF, JPEG, PNG y MP4 hasta 2 GB', () => {
    for (const tipo of ['application/pdf', 'image/jpeg', 'image/png', 'video/mp4']) {
      expect(validarArchivo(archivo('a', tipo))).toBeNull();
    }
    expect(validarArchivo(archivo('v.mp4', 'video/mp4', TAMANO_MAXIMO))).toBeNull();
  });

  it('rechaza otros tipos, archivos vacíos y de más de 2 GB', () => {
    expect(validarArchivo(archivo('a.exe', 'application/x-msdownload'))).toContain('PDF, JPG, PNG o MP4');
    expect(validarArchivo(archivo('a.gif', 'image/gif'))).toContain('PDF, JPG, PNG o MP4');
    expect(validarArchivo(archivo('sin-tipo', ''))).toContain('PDF, JPG, PNG o MP4');
    expect(validarArchivo(archivo('a.pdf', 'application/pdf', 0))).toContain('vacío');
    expect(validarArchivo(archivo('v.mp4', 'video/mp4', TAMANO_MAXIMO + 1))).toContain('2 GB');
  });

  it('las carpetas son las 11 de docs/04', () => {
    expect(CARPETAS.map((c) => c.id)).toEqual(['medicina-general', 'cardiologia', 'dermatologia', 'pediatria',
      'ginecologia', 'oftalmologia', 'traumatologia', 'neurologia', 'laboratorio', 'imagenes', 'otros']);
  });
});
