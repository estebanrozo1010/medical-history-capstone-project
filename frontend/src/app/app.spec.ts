import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth/auth.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), { provide: AuthService, useValue: { email } }],
    }).compileComponents();
  });

  const email = signal<string | null>(null);

  it('muestra la cabecera y el contenedor de rutas', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('header')?.textContent).toContain('Historial médico');
    expect(el.querySelector('main router-outlet')).toBeTruthy();
  });

  it('muestra el menú solo con sesión iniciada', async () => {
    const fixture = TestBed.createComponent(App);
    const el = fixture.nativeElement as HTMLElement;
    email.set(null);
    await fixture.whenStable();
    expect(el.querySelector('nav')).toBeNull();

    email.set('a@b.co');
    await fixture.whenStable();
    const enlaces = [...el.querySelectorAll('nav a')].map((a) => a.getAttribute('href'));
    expect(enlaces).toEqual(['/carpetas', '/subir', '/perfil']);
  });
});
