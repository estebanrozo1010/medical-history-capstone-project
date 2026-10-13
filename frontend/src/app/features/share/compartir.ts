import { HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, Injector, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { LinkCreado, LinksApi } from '../../core/api/links.api';
import { CopiarUrl } from './copiar-url';
import { Seleccion } from './seleccion';

export const DURACIONES = [
  { minutos: 15, texto: '15 minutos' },
  { minutos: 60, texto: '1 hora' },
  { minutos: 120, texto: '2 horas' },
  { minutos: 1440, texto: '24 horas' },
];

export const fechaHora = (segundos: number) =>
  new Date(segundos * 1000).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' });

const mensajeError = (e: unknown) => {
  const r = e instanceof HttpErrorResponse ? e : null;
  return r?.status === 0 ? 'No se pudo conectar. Revisa tu conexión e inténtalo de nuevo.'
    : r?.status === 404 ? 'Alguno de los archivos ya no existe. Quita la selección, recarga y vuelve a elegirlos.'
    : (r?.status === 400 || r?.status === 409) && r.error?.message ? r.error.message
    : 'Ocurrió un error. Inténtalo de nuevo.';
};

/** Bloque para crear un link con los archivos elegidos (de una o varias carpetas) y copiar su URL. */
@Component({
  selector: 'app-compartir',
  imports: [RouterLink, CopiarUrl],
  template: `
    @if (seleccion.archivos(); as elegidos) {
      @if (elegidos.length) {
        <section class="compartir" aria-labelledby="titulo-compartir">
          <h2 id="titulo-compartir">Compartir</h2>
          <p>
            {{ elegidos.length }} {{ elegidos.length === 1 ? 'archivo seleccionado' : 'archivos seleccionados' }}
            @if (seleccion.llena()) {
              (máximo 50 por link)
            }
          </p>
          <p class="ayuda">{{ nombres(elegidos) }}</p>
          <div class="campo">
            <label for="duracion">Duración del link</label>
            <select id="duracion" (change)="ttl.set(+$any($event.target).value)">
              @for (d of duraciones; track d.minutos) {
                <option [value]="d.minutos" [selected]="d.minutos === ttl()">{{ d.texto }}</option>
              }
            </select>
          </div>
          <button [disabled]="ocupado()" (click)="crear()">Crear link</button>
          <button class="secundario" [disabled]="ocupado()" (click)="seleccion.limpiar()">Quitar selección</button>
        </section>
      }
    }
    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    }
    <p role="status">{{ aviso() }}</p>
    @if (creado(); as c) {
      <section class="compartir" aria-labelledby="link-creado">
        <h2 id="link-creado" tabindex="-1">Link creado</h2>
        <app-copiar-url campoId="url-creada" [url]="c.url" />
        <p class="ayuda">Vence: {{ fecha(c.expiresAt) }}</p>
        <p><a routerLink="/links">Ver mis links compartidos</a></p>
      </section>
    }
  `,
})
export class Compartir {
  private readonly api = inject(LinksApi);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  protected readonly seleccion = inject(Seleccion);
  protected readonly duraciones = DURACIONES;
  protected readonly ttl = signal(60);
  protected readonly ocupado = signal(false);
  protected readonly creado = signal<LinkCreado | null>(null);
  protected readonly aviso = signal('');
  protected readonly error = signal('');
  protected readonly fecha = fechaHora;

  protected nombres(l: { nombre: string }[]) {
    return l.map((a) => a.nombre).join(', ');
  }

  protected async crear() {
    this.ocupado.set(true);
    this.aviso.set('');
    this.error.set('');
    try {
      this.creado.set(await firstValueFrom(this.api.crear(this.seleccion.archivos(), this.ttl())));
      this.seleccion.limpiar();
      this.aviso.set('Link creado. Copia el enlace para compartirlo.');
      // El botón Crear link desaparece con la selección: el foco va al título del link creado
      afterNextRender(() => this.document.getElementById('link-creado')?.focus(), { injector: this.injector });
    } catch (e) {
      this.error.set(mensajeError(e));
    } finally {
      this.ocupado.set(false);
    }
  }
}
