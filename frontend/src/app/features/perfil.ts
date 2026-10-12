import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { DatosPerfil, ProfileApi } from '../core/api/profile.api';
import { AuthService } from '../core/auth/auth.service';

const FECHA_MINIMA = '1900-01-01';
// en-CA formatea AAAA-MM-DD en hora local (el back compara con hoy en UTC, que nunca es anterior en Colombia)
const hoy = () => new Date().toLocaleDateString('en-CA');
// Las fechas AAAA-MM-DD se comparan bien como texto
const fechaValida = (c: AbstractControl<string>) =>
  !c.value || (c.value >= FECHA_MINIMA && c.value <= hoy()) ? null : { rango: true };

type Campo = 'nombre' | 'cedula' | 'fechaNacimiento' | 'genero';

/** GET al entrar: con 404 muestra el formulario de primer registro (POST); si existe, lo deja editar (PUT). */
@Component({
  selector: 'app-perfil',
  imports: [ReactiveFormsModule],
  template: `
    @switch (estado()) {
      @case ('cargando') {
        <p role="status">Cargando perfil…</p>
      }
      @case ('error') {
        <h1>Mi perfil</h1>
        <p class="error" role="alert">No se pudo cargar tu perfil. Recarga la página para intentarlo de nuevo.</p>
      }
      @default {
        <h1>{{ estado() === 'nuevo' ? 'Completa tu perfil' : 'Mi perfil' }}</h1>
        <p>Correo: {{ auth.email() }}</p>
        @if (guardado()) {
          <p class="exito" role="status">Perfil guardado.</p>
        }
        <form [formGroup]="form" (ngSubmit)="enviar()" novalidate>
          <div class="campo">
            <label for="nombre">Nombre completo</label>
            <input id="nombre" formControlName="nombre" autocomplete="name" maxlength="100"
              [attr.aria-invalid]="mostrarError('nombre')" aria-describedby="nombre-error" />
            @if (mostrarError('nombre')) {
              <p id="nombre-error" class="error">Ingresa tu nombre (máximo 100 caracteres).</p>
            }
          </div>
          <div class="campo">
            <label for="cedula">Cédula</label>
            <input id="cedula" formControlName="cedula" inputmode="numeric"
              [attr.aria-invalid]="mostrarError('cedula')" aria-describedby="cedula-error" />
            @if (mostrarError('cedula')) {
              <p id="cedula-error" class="error">La cédula debe tener de 5 a 15 dígitos, sin puntos ni espacios.</p>
            }
          </div>
          <div class="campo">
            <label for="fechaNacimiento">Fecha de nacimiento</label>
            <input id="fechaNacimiento" type="date" formControlName="fechaNacimiento" autocomplete="bday"
              [min]="fechaMinima" [max]="hoy"
              [attr.aria-invalid]="mostrarError('fechaNacimiento')" aria-describedby="fechaNacimiento-error" />
            @if (mostrarError('fechaNacimiento')) {
              <p id="fechaNacimiento-error" class="error">Ingresa una fecha entre 01/01/1900 y hoy.</p>
            }
          </div>
          <div class="campo">
            <label for="genero">Género</label>
            <select id="genero" formControlName="genero"
              [attr.aria-invalid]="mostrarError('genero')" aria-describedby="genero-error">
              <option value="" disabled>Selecciona una opción</option>
              <option value="F">Femenino</option>
              <option value="M">Masculino</option>
              <option value="O">Otro</option>
            </select>
            @if (mostrarError('genero')) {
              <p id="genero-error" class="error">Selecciona una opción.</p>
            }
          </div>
          @if (error()) {
            <p class="error" role="alert">{{ error() }}</p>
          }
          <button type="submit" [disabled]="guardando()">
            {{ guardando() ? 'Guardando…' : estado() === 'nuevo' ? 'Crear perfil' : 'Guardar cambios' }}
          </button>
        </form>
      }
    }
    <p><button type="button" class="secundario" (click)="salir()">Cerrar sesión</button></p>
  `,
})
export class Perfil {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ProfileApi);
  private readonly router = inject(Router);
  protected readonly fechaMinima = FECHA_MINIMA;
  protected readonly hoy = hoy();
  protected readonly estado = signal<'cargando' | 'error' | 'nuevo' | 'existente'>('cargando');
  protected readonly guardando = signal(false);
  protected readonly guardado = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    nombre: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(/\S/)]],
    cedula: ['', [Validators.required, Validators.pattern(/^\d{5,15}$/)]],
    fechaNacimiento: ['', [Validators.required, fechaValida]],
    genero: ['' as DatosPerfil['genero'] | '', Validators.required],
  });

  constructor() {
    this.cargar();
  }

  private async cargar() {
    try {
      const { email, ...datos } = await firstValueFrom(this.api.get());
      this.form.setValue(datos);
      this.estado.set('existente');
    } catch (e) {
      this.estado.set(e instanceof HttpErrorResponse && e.status === 404 ? 'nuevo' : 'error');
    }
  }

  protected mostrarError(campo: Campo) {
    const c = this.form.controls[campo];
    return c.invalid && c.touched;
  }

  protected async enviar() {
    if (this.form.invalid) return this.form.markAllAsTouched();
    const datos = this.form.getRawValue() as DatosPerfil;
    this.guardando.set(true);
    this.guardado.set(false);
    this.error.set('');
    try {
      const nuevo = this.estado() === 'nuevo';
      const { email, ...guardados } = await firstValueFrom(nuevo ? this.api.create(datos) : this.api.update(datos));
      this.form.setValue(guardados);
      this.estado.set('existente');
      this.guardado.set(true);
    } catch (e) {
      const http = e instanceof HttpErrorResponse ? e : null;
      this.error.set(
        http?.status === 400 && http.error?.message ? http.error.message : 'No se pudo guardar el perfil. Inténtalo de nuevo.',
      );
    } finally {
      this.guardando.set(false);
    }
  }

  protected salir() {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }
}
