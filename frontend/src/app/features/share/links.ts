import { HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, Injector, afterNextRender, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Link, LinksApi } from '../../core/api/links.api';
import { PERMISOS, fechaHora } from './compartir';
import { CopiarUrl } from './copiar-url';

/** Links vigentes del usuario: archivos, expiración, switch activo/inactivo (PATCH) y URL para copiar. */
@Component({
  selector: 'app-links',
  imports: [RouterLink, CopiarUrl],
  template: `
    <h1 id="titulo" tabindex="-1">Links compartidos</h1>
    <p role="status">{{ aviso() }}</p>
    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
    }
    @switch (estado()) {
      @case ('cargando') {
        <p role="status">Cargando links…</p>
      }
      @case ('error') {
        <p class="error" role="alert">No se pudieron cargar tus links. Recarga la página para intentarlo de nuevo.</p>
      }
      @default {
        @if (links().length) {
          <ul class="lista">
            @for (l of links(); track l.token) {
              <li>
                <span class="nombre">{{ nombres(l) || 'Sus archivos ya se eliminaron' }}</span>
                <span class="ayuda">{{ l.archivos.length }} {{ l.archivos.length === 1 ? 'archivo' : 'archivos' }} · {{ permiso(l) }} · Vence: {{ fecha(l.expiresAt) }}</span>
                <span class="acciones">
                  <label class="casilla">
                    <input type="checkbox" role="switch" [attr.aria-label]="'Link activo: ' + nombres(l)" [checked]="l.activo"
                      [disabled]="ocupado() === l.token" (change)="cambiar(l, $any($event.target))" />
                    Activo
                  </label>
                </span>
                <app-copiar-url [campoId]="'url-' + l.token" [url]="url(l)" etiqueta="Enlace" [detalle]="nombres(l)" />
              </li>
            }
          </ul>
        } @else {
          <p>No tienes links vigentes. Elige archivos limpios en <a routerLink="/carpetas">tus carpetas</a> para compartirlos.</p>
        }
      }
    }
  `,
})
export class Links {
  private readonly api = inject(LinksApi);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  protected readonly estado = signal<'cargando' | 'error' | 'listo'>('cargando');
  protected readonly links = signal<Link[]>([]);
  protected readonly ocupado = signal<string | null>(null);
  protected readonly aviso = signal('');
  protected readonly error = signal('');
  protected readonly fecha = fechaHora;

  constructor() {
    this.cargar();
  }

  private async cargar() {
    try {
      this.links.set(await firstValueFrom(this.api.listar()));
      this.estado.set('listo');
    } catch {
      this.estado.set('error');
    }
  }

  /** El backend no conserva el orden en que se eligieron (SS): se ordenan por nombre. */
  protected nombres(l: Link) {
    return l.archivos.map((a) => a.nombre).sort((a, b) => a.localeCompare(b, 'es')).join(', ');
  }

  protected permiso(l: Link) {
    return PERMISOS.find((p) => p.valor === l.permiso)?.texto;
  }

  /** Mismo valor que arma mh-links con ALLOWED_ORIGIN mientras el front se sirva desde ese origen. */
  protected url(l: Link) {
    return `${this.document.location.origin}/compartido/${l.token}`;
  }

  protected async cambiar(l: Link, sw: HTMLInputElement) {
    this.ocupado.set(l.token);
    this.aviso.set('');
    this.error.set('');
    try {
      const r = await firstValueFrom(this.api.cambiarEstado(l.token, sw.checked));
      this.links.update((lista) => lista.map((x) => (x.token === l.token ? { ...x, activo: r.activo } : x)));
      this.aviso.set(r.activo ? `Se activó el link de ${this.nombres(l)}.` : `Se desactivó el link de ${this.nombres(l)}: ya no se puede abrir.`);
    } catch (e) {
      const r = e instanceof HttpErrorResponse ? e : null;
      if (r?.status === 404) {
        this.links.update((lista) => lista.filter((x) => x.token !== l.token));
        this.error.set('El link ya no existe o venció; se quitó de la lista.');
        // Su switch ya no existe: el foco vuelve al título
        afterNextRender(() => this.document.getElementById('titulo')?.focus(), { injector: this.injector });
      } else {
        sw.checked = l.activo;
        this.error.set(r?.status === 0 ? 'No se pudo conectar. Revisa tu conexión e inténtalo de nuevo.' : 'Ocurrió un error. Inténtalo de nuevo.');
      }
    } finally {
      this.ocupado.set(null);
    }
  }
}
