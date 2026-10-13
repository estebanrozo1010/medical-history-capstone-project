import { HttpBackend, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, Injectable, Injector, afterNextRender, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Config } from '../../../core/config';
import { tamano } from '../../folders/carpeta';
import { fechaHora } from '../compartir';

export interface ArchivoCompartido {
  fileId: string;
  nombre: string;
  contentType: string;
  size: number;
}

/** Rutas públicas de mh-links. */
@Injectable({ providedIn: 'root' })
export class CompartidoApi {
  // HttpBackend salta los interceptores: sin Authorization (ni preflight de CORS) aunque haya sesión
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly config = inject(Config);

  url(token: string) {
    return `${this.config.value.apiUrl}/s/${encodeURIComponent(token)}`;
  }

  /** 410 si no existe, está apagado, venció o el token está mal formado. */
  resolver(token: string) {
    return this.http.get<{ expiresAt: number; archivos: ArchivoCompartido[] }>(this.url(token));
  }
}

/** Pantalla sin sesión de un link compartido: archivos con Ver y Descargar, o "enlace no disponible" ante 410. */
@Component({
  selector: 'app-compartido',
  host: { '(document:visibilitychange)': 'alVolver()', '(window:pageshow)': 'alVolver()' },
  template: `
    <h1 id="titulo" tabindex="-1">{{ estado() === 'no-disponible' ? 'Enlace no disponible' : 'Archivos compartidos contigo' }}</h1>
    @switch (estado()) {
      @case ('cargando') {
        <p role="status">Cargando archivos…</p>
      }
      @case ('error') {
        <p class="error" role="alert">No se pudieron cargar los archivos. Revisa tu conexión y recarga la página.</p>
      }
      @case ('no-disponible') {
        <p>Este enlace venció o quien lo compartió lo desactivó. Pídele uno nuevo.</p>
      }
      @default {
        <p class="ayuda">Disponible hasta: {{ fecha(expiresAt()) }}</p>
        @if (archivos().length) {
          <ul class="lista">
            @for (a of archivos(); track a.fileId) {
              <li>
                <span class="nombre">{{ a.nombre }}</span>
                <span class="ayuda">{{ tamano(a.size) }}</span>
                <span class="acciones">
                  <a [href]="archivoUrl(a) + '?modo=ver'" target="_blank" rel="noopener" [attr.aria-label]="'Ver ' + a.nombre + ' (se abre en otra pestaña)'">Ver</a>
                  <a [href]="archivoUrl(a)" [attr.aria-label]="'Descargar ' + a.nombre">Descargar</a>
                </span>
              </li>
            }
          </ul>
        } @else {
          <p>Los archivos de este enlace ya no están disponibles.</p>
        }
      }
    }
  `,
})
export class Compartido {
  private readonly api = inject(CompartidoApi);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly token = inject(ActivatedRoute).snapshot.paramMap.get('token')!;
  protected readonly estado = signal<'cargando' | 'error' | 'no-disponible' | 'listo'>('cargando');
  protected readonly archivos = signal<ArchivoCompartido[]>([]);
  protected readonly expiresAt = signal(0);
  protected readonly fecha = fechaHora;
  protected readonly tamano = tamano;

  constructor() {
    this.cargar();
  }

  /** Un 404 o 410 de Ver/Descargar lo muestra el navegador como JSON: al volver se recarga la lista. */
  protected alVolver() {
    if (this.document.visibilityState === 'visible' && this.estado() !== 'cargando') this.cargar();
  }

  /** Destino de Ver y Descargar: navegación, no HttpClient (el 302 lleva a una URL firmada de 1 min). */
  protected archivoUrl(a: ArchivoCompartido) {
    return `${this.api.url(this.token)}/files/${encodeURIComponent(a.fileId)}`;
  }

  private async cargar() {
    const antes = this.estado();
    try {
      const r = await firstValueFrom(this.api.resolver(this.token));
      // Sin orden garantizado (fileIds es un SS)
      this.archivos.set([...r.archivos].sort((a, b) => a.nombre.localeCompare(b.nombre, 'es')));
      this.expiresAt.set(r.expiresAt);
      this.estado.set('listo');
    } catch (e) {
      this.estado.set(e instanceof HttpErrorResponse && e.status === 410 ? 'no-disponible' : 'error');
    }
    // La lista desapareció (y con ella el foco): el título anuncia el nuevo estado
    if (antes === 'listo' && this.estado() !== 'listo') {
      afterNextRender(() => this.document.getElementById('titulo')?.focus(), { injector: this.injector });
    }
  }
}
