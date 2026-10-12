import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';

/** Marcador de la ruta protegida; la Tarea 7 lo reemplaza por la pantalla de perfil. */
@Component({
  selector: 'app-perfil',
  template: `
    <h1>Perfil</h1>
    <p>Sesión iniciada como {{ auth.email() }}.</p>
    <button type="button" (click)="salir()">Cerrar sesión</button>
  `,
})
export class Perfil {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected salir() {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}
