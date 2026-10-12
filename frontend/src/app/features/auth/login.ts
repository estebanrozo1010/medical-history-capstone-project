import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { mensajeError } from '../../core/auth/errores';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Iniciar sesión</h1>
    @if (confirmado) {
      <p class="exito" role="status">Cuenta confirmada. Ya puedes iniciar sesión.</p>
    }
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
        <input id="password" type="password" formControlName="password" autocomplete="current-password"
          [attr.aria-invalid]="mostrarError('password')" aria-describedby="password-error" />
        @if (mostrarError('password')) {
          <p id="password-error" class="error">Ingresa tu contraseña.</p>
        }
      </div>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      <button type="submit" [disabled]="cargando()">{{ cargando() ? 'Ingresando…' : 'Ingresar' }}</button>
    </form>
    <p>¿No tienes cuenta? <a routerLink="/registro">Regístrate</a></p>
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  protected readonly confirmado = this.params.has('confirmado');
  protected readonly cargando = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: [this.params.get('email') ?? '', [Validators.required, Validators.email]],
    password: ['', Validators.required],
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
      await this.auth.login(email, password);
      await this.router.navigateByUrl('/perfil');
    } catch (e) {
      if ((e as { code?: string }).code === 'UserNotConfirmedException') {
        await this.router.navigate(['/confirmar'], { queryParams: { email } });
      } else {
        this.error.set(mensajeError(e));
      }
    } finally {
      this.cargando.set(false);
    }
  }
}
