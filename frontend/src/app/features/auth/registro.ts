import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { mensajeError } from '../../core/auth/errores';

@Component({
  selector: 'app-registro',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Crear cuenta</h1>
    <form [formGroup]="form" (ngSubmit)="enviar()" novalidate>
      <div class="campo">
        <label for="email">Correo electrónico</label>
        <input id="email" type="email" formControlName="email" autocomplete="email"
          [attr.aria-invalid]="mostrarError('email')" aria-describedby="email-error" />
        @if (mostrarError('email')) {
          <p id="email-error" class="error">Ingresa un correo válido.</p>
        }
      </div>
      <div class="campo">
        <label for="password">Contraseña</label>
        <input id="password" type="password" formControlName="password" autocomplete="new-password"
          [attr.aria-invalid]="mostrarError('password')" aria-describedby="password-ayuda password-error" />
        <p id="password-ayuda" class="ayuda">Mínimo 8 caracteres, con mayúscula, minúscula y número.</p>
        @if (mostrarError('password')) {
          <p id="password-error" class="error">La contraseña no cumple los requisitos.</p>
        }
      </div>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <button type="submit" [disabled]="cargando()">{{ cargando() ? 'Creando cuenta…' : 'Crear cuenta' }}</button>
    </form>
    <p>¿Ya tienes cuenta? <a routerLink="/login">Inicia sesión</a></p>
  `,
})
export class Registro {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly cargando = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.pattern(/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/)]],
  });

  protected mostrarError(campo: 'email' | 'password') {
    const c = this.form.controls[campo];
    return c.invalid && c.touched;
  }

  protected async enviar() {
    if (this.form.invalid) return this.form.markAllAsTouched();
    const { email, password } = this.form.getRawValue();
    this.cargando.set(true);
    this.error.set('');
    try {
      await this.auth.signUp(email, password);
      await this.router.navigate(['/confirmar'], { queryParams: { email } });
    } catch (e) {
      this.error.set(mensajeError(e));
    } finally {
      this.cargando.set(false);
    }
  }
}
