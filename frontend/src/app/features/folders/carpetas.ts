import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Archivo, CARPETAS, FilesApi } from '../../core/api/files.api';
import { Compartir } from '../share/compartir';

/** Especialidades con su total de archivos, calculado con un solo GET /files (docs/04). */
@Component({
  selector: 'app-carpetas',
  imports: [RouterLink, Compartir],
  template: `
    <h1>Mis carpetas</h1>
    @switch (estado()) {
      @case ('cargando') {
        <p role="status">Cargando carpetas…</p>
      }
      @case ('error') {
        <p class="error" role="alert">No se pudieron cargar tus archivos. Recarga la página para intentarlo de nuevo.</p>
      }
      @default {
        <ul class="lista">
          @for (c of carpetas; track c.id) {
            <li>
              <a [routerLink]="['/carpetas', c.id]">{{ c.nombre }}</a>
              <span class="ayuda">{{ totales()[c.id] ?? 0 }} {{ totales()[c.id] === 1 ? 'archivo' : 'archivos' }}</span>
            </li>
          }
        </ul>
      }
    }
    <app-compartir />
    <p><a routerLink="/subir">Subir un archivo</a></p>
  `,
})
export class Carpetas {
  private readonly api = inject(FilesApi);
  protected readonly carpetas = CARPETAS;
  protected readonly estado = signal<'cargando' | 'error' | 'listo'>('cargando');
  private readonly archivos = signal<Archivo[]>([]);
  protected readonly totales = computed(() => {
    const t: Partial<Record<string, number>> = {};
    for (const a of this.archivos()) t[a.carpeta] = (t[a.carpeta] ?? 0) + 1;
    return t;
  });

  constructor() {
    this.cargar();
  }

  private async cargar() {
    try {
      this.archivos.set(await firstValueFrom(this.api.listar()));
      this.estado.set('listo');
    } catch {
      this.estado.set('error');
    }
  }
}
