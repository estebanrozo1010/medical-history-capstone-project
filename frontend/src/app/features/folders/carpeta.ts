import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Archivo, CARPETAS, FilesApi } from '../../core/api/files.api';

const ESTADOS: Record<Archivo['status'], string> = {
  PENDING: 'Pendiente',
  CLEAN: 'Limpio',
  INFECTED: 'Infectado',
  REJECTED: 'Rechazado',
  SCAN_FAILED: 'Falló la revisión',
  UPLOAD_EXPIRED: 'Subida vencida',
};

const tamano = (b: number) => {
  const [valor, unidad] = b < 1024 ? [b, 'B'] : b < 1024 ** 2 ? [b / 1024, 'KB'] : b < 1024 ** 3 ? [b / 1024 ** 2, 'MB'] : [b / 1024 ** 3, 'GB'];
  return `${valor.toLocaleString('es-CO', { maximumFractionDigits: 1 })} ${unidad}`;
};

/** Archivos de una carpeta: GET /files?carpeta= con el estado en español. */
@Component({
  selector: 'app-carpeta',
  imports: [RouterLink],
  template: `
    @if (carpeta; as c) {
      <h1>{{ c.nombre }}</h1>
      @switch (estado()) {
        @case ('cargando') {
          <p role="status">Cargando archivos…</p>
        }
        @case ('error') {
          <p class="error" role="alert">No se pudieron cargar los archivos. Recarga la página para intentarlo de nuevo.</p>
        }
        @default {
          @if (archivos().length) {
            <ul class="lista">
              @for (a of archivos(); track a.fileId) {
                <li>
                  <span class="nombre">{{ a.nombre }}</span>
                  <span class="ayuda">{{ tamano(a.size) }} · {{ fecha(a.createdAt) }}</span>
                  <span [class]="'estado estado-' + a.status">{{ estados[a.status] ?? a.status }}</span>
                </li>
              }
            </ul>
          } @else {
            <p>Esta carpeta no tiene archivos.</p>
          }
        }
      }
      <p><a routerLink="/subir" [queryParams]="{ carpeta: c.id }">Subir a esta carpeta</a></p>
    } @else {
      <h1>Carpeta no encontrada</h1>
    }
    <p><a routerLink="/carpetas">Volver a mis carpetas</a></p>
  `,
})
export class Carpeta {
  private readonly api = inject(FilesApi);
  protected readonly carpeta = CARPETAS.find((c) => c.id === inject(ActivatedRoute).snapshot.paramMap.get('nombre'));
  protected readonly estado = signal<'cargando' | 'error' | 'listo'>('cargando');
  protected readonly archivos = signal<Archivo[]>([]);
  protected readonly estados: Partial<Record<string, string>> = ESTADOS;
  protected readonly tamano = tamano;

  constructor() {
    if (this.carpeta) this.cargar(this.carpeta.id);
  }

  private async cargar(carpeta: string) {
    try {
      this.archivos.set(await firstValueFrom(this.api.listar(carpeta)));
      this.estado.set('listo');
    } catch {
      this.estado.set('error');
    }
  }

  protected fecha(segundos: number) {
    return new Date(segundos * 1000).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' });
  }
}
