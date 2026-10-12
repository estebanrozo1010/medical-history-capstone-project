import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Config } from '../config';
import { DatosPerfil, ProfileApi } from './profile.api';

const API = 'https://api.test/v1';
const DATOS: DatosPerfil = { nombre: 'Ana', cedula: '12345', fechaNacimiento: '1990-05-01', genero: 'F' };

describe('ProfileApi', () => {
  let api: ProfileApi;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(Config).value = { apiUrl: API, userPoolId: '', clientId: '', region: '' };
    api = TestBed.inject(ProfileApi);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('get hace GET {apiUrl}/profile', () => {
    let perfil: unknown;
    api.get().subscribe((p) => (perfil = p));
    const req = backend.expectOne(`${API}/profile`);
    expect(req.request.method).toBe('GET');
    req.flush({ email: 'a@b.co', ...DATOS });
    expect(perfil).toEqual({ email: 'a@b.co', ...DATOS });
  });

  it('create hace POST {apiUrl}/profile con los datos', () => {
    api.create(DATOS).subscribe();
    const req = backend.expectOne(`${API}/profile`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(DATOS);
    req.flush({ email: 'a@b.co', ...DATOS }, { status: 201, statusText: 'Created' });
  });

  it('update hace PUT {apiUrl}/profile con los datos', () => {
    api.update(DATOS).subscribe();
    const req = backend.expectOne(`${API}/profile`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(DATOS);
    req.flush({ email: 'a@b.co', ...DATOS });
  });
});
