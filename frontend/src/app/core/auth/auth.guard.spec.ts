import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  const auth = { idToken: vi.fn() };
  const ejecutar = () =>
    TestBed.runInInjectionContext(() => authGuard({} as never, {} as never)) as Promise<boolean | UrlTree>;

  beforeEach(() =>
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: auth }] }),
  );

  it('sin sesión redirige a /login', async () => {
    auth.idToken.mockResolvedValue(null);
    const r = await ejecutar();
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/login');
  });

  it('con sesión deja pasar', async () => {
    auth.idToken.mockResolvedValue('tok');
    expect(await ejecutar()).toBe(true);
  });
});
