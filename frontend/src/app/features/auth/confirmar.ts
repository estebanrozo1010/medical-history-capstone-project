import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { mensajeError } from '../../core/auth/errores';

@Component({
  selector: 'app-confirmar',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Confirmar cuenta</h1>
    <p>Te enviamos un código de verificación por correo.</p>
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
        <label for="codigo">Código de verificación</label>
        <input id="codigo" type="text" inputmode="numeric" formControlName="codigo" autocomplete="one-time-code"
          [attr.aria-invalid]="mostrarError('codigo')" aria-describedby="codigo-error" />
        @if (mostrarError('codigo')) {
          <p id="codigo-error" class="error">Ingresa el código de 6 dígitos que recibiste.</p>
        }
      </div>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      @if (aviso()) {
        <p class="exito" role="status">{{ aviso() }}</p>
      }
      <button type="submit" [disabled]="cargando()">{{ cargando() ? 'Confirmando…' : 'Confirmar' }}</button>
      <button type="button" class="secundario" (click)="reenviar()" [disabled]="cargando()">Reenviar código</button>
    </form>
    <p><a routerLink="/login">Volver a iniciar sesión</a></p>
  `,
})
export class Confirmar {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly cargando = signal(false);
  protected readonly error = signal('');
  protected readonly aviso = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: [inject(ActivatedRoute).snapshot.queryParamMap.get('email') ?? '', [Validators.required, Validators.email]],
    codigo: ['', [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]],
  });

  protected mostrarError(campo: 'email' | 'codigo') {
    const c = this.form.controls[campo];
    return c.invalid && c.touched;
  }

  protected async enviar() {
    if (this.form.invalid) return this.form.markAllAsTouched();
    const { email, codigo } = this.form.getRawValue();
    await this.ejecutar(async () => {
      await this.auth.confirm(email, codigo.trim());
      await this.router.navigate(['/login'], { queryParams: { confirmado: 1, email } });
    });
  }

  protected async reenviar() {
    const email = this.form.controls.email;
    if (email.invalid) return email.markAsTouched();
    await this.ejecutar(async () => {
      await this.auth.resendCode(email.value);
      this.aviso.set('Te enviamos un código nuevo.');
    });
  }

  private async ejecutar(accion: () => Promise<unknown>) {
    this.cargando.set(true);
    this.error.set('');
    this.aviso.set('');
    try {
      await accion();
    } catch (e) {
      this.error.set(mensajeError(e));
    } finally {
      this.cargando.set(false);
    }
  }
}
