import { HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, Injector, afterNextRender, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Archivo, CARPETAS, FilesApi } from '../../core/api/files.api';
import { Compartir } from '../share/compartir';
import { Seleccion } from '../share/seleccion';

export const ESTADOS: Record<Archivo['status'], string> = {
  PENDING: 'Pendiente',
  CLEAN: 'Limpio',
  INFECTED: 'Infectado',
  REJECTED: 'Rechazado',
  SCAN_FAILED: 'Falló la revisión',
  UPLOAD_EXPIRED: 'Subida vencida',
};

export const tamano = (b: number) => {
  const [valor, unidad] = b < 1024 ? [b, 'B'] : b < 1024 ** 2 ? [b / 1024, 'KB'] : b < 1024 ** 3 ? [b / 1024 ** 2, 'MB'] : [b / 1024 ** 3, 'GB'];
  return `${valor.toLocaleString('es-CO', { maximumFractionDigits: 1 })} ${unidad}`;
};

/** 409 trae el motivo del backend en español (no CLEAN, réplica en curso). */
const mensajeError = (e: unknown) => {
  const r = e instanceof HttpErrorResponse ? e : null;
  return r?.status === 0 ? 'No se pudo conectar. Revisa tu conexión e inténtalo de nuevo.'
    : r?.status === 404 ? 'El archivo ya no existe. Recarga la página.'
    : r?.status === 409 && r.error?.message ? r.error.message
    : 'Ocurrió un error. Inténtalo de nuevo.';
};

/** Archivos de una carpeta: GET /files?carpeta= con el estado en español; descargar y elegir para compartir (solo CLEAN), y eliminar. */
@Component({
  selector: 'app-carpeta',
  imports: [RouterLink, Compartir],
  template: `
    @if (carpeta; as c) {
      <h1 id="titulo" tabindex="-1">{{ c.nombre }}</h1>
      <p role="status">{{ aviso() }}</p>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      @switch (estado()) {
        @case ('cargando') {
          <p role="status">Cargando archivos…</p>
        }
        @case ('error') {
          <p class="error" role="alert">No se pudieron cargar los archivos. Recarga la página para intentarlo de nuevo.</p>
        }
        @default {
          @if (archivos().length) {
            @if (limpios().length) {
              <p class="casilla">
                <input type="checkbox" id="todos-limpios" [checked]="todosElegidos()" (change)="elegirTodos($any($event.target).checked)" />
                <label for="todos-limpios">Seleccionar todos los limpios para compartir</label>
              </p>
            }
            <ul class="lista">
              @for (a of archivos(); track a.fileId) {
                <li>
                  <span class="nombre">
                    @if (a.status === 'CLEAN') {
                      <input type="checkbox" [attr.aria-label]="'Seleccionar ' + a.nombre" [checked]="seleccion.tiene(a.fileId)"
                        [disabled]="!seleccion.tiene(a.fileId) && seleccion.llena()" (change)="seleccion.alternar(a)" />
                    }
                    {{ a.nombre }}
                  </span>
                  <span class="ayuda">{{ tamano(a.size) }} · {{ fecha(a.createdAt) }}</span>
                  <span [class]="'estado estado-' + a.status">{{ estados[a.status] ?? a.status }}</span>
                  <span class="acciones">
                    @if (confirmando() === a.fileId) {
                      <span class="ayuda">¿Eliminar este archivo? No se puede deshacer.</span>
                      <button class="peligro" [disabled]="ocupado()" (click)="eliminar(a)">Confirmar eliminar</button>
                      <button class="secundario" [id]="'cancelar-' + a.fileId" [disabled]="ocupado()" (click)="cancelar(a)">Cancelar</button>
                    } @else {
                      @if (a.status === 'CLEAN') {
                        <button class="secundario" [attr.aria-label]="'Descargar ' + a.nombre" [disabled]="ocupado()"
                          (click)="descargar(a)">Descargar</button>
                      }
                      <button class="secundario" [id]="'eliminar-' + a.fileId" [attr.aria-label]="'Eliminar ' + a.nombre"
                        [disabled]="ocupado()" (click)="confirmar(a)">Eliminar</button>
                    }
                  </span>
                </li>
              }
            </ul>
          } @else {
            <p>Esta carpeta no tiene archivos.</p>
          }
        }
      }
      <app-compartir />
      <p><a routerLink="/subir" [queryParams]="{ carpeta: c.id }">Subir a esta carpeta</a></p>
    } @else {
      <h1>Carpeta no encontrada</h1>
    }
    <p><a routerLink="/carpetas">Volver a mis carpetas</a></p>
  `,
})
export class Carpeta {
  private readonly api = inject(FilesApi);
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  protected readonly carpeta = CARPETAS.find((c) => c.id === inject(ActivatedRoute).snapshot.paramMap.get('nombre'));
  protected readonly estado = signal<'cargando' | 'error' | 'listo'>('cargando');
  protected readonly archivos = signal<Archivo[]>([]);
  protected readonly seleccion = inject(Seleccion);
  protected readonly limpios = computed(() => this.archivos().filter((a) => a.status === 'CLEAN'));
  protected readonly todosElegidos = computed(() => this.limpios().every((a) => this.seleccion.tiene(a.fileId)));
  protected readonly confirmando = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly aviso = signal('');
  protected readonly error = signal('');
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

  protected async descargar(a: Archivo) {
    await this.operar(async () => this.abrir((await firstValueFrom(this.api.urlDescarga(a.fileId))).url));
  }

  /** Solo afecta a los limpios de esta carpeta; lo elegido en otras se conserva. */
  protected elegirTodos(marcar: boolean) {
    if (marcar) this.seleccion.agregar(this.limpios());
    else this.seleccion.quitar(this.limpios().map((a) => a.fileId));
  }

  /** Primer clic: pide confirmación con el foco en Cancelar (la acción menos destructiva). */
  protected confirmar(a: Archivo) {
    this.confirmando.set(a.fileId);
    this.enfocar(`cancelar-${a.fileId}`);
  }

  protected cancelar(a: Archivo) {
    this.confirmando.set(null);
    this.enfocar(`eliminar-${a.fileId}`);
  }

  protected async eliminar(a: Archivo) {
    await this.operar(async () => {
      await firstValueFrom(this.api.eliminar(a.fileId), { defaultValue: null });
      this.archivos.update((l) => l.filter((x) => x.fileId !== a.fileId));
      this.seleccion.quitar([a.fileId]);
      this.confirmando.set(null);
      this.aviso.set(`Se eliminó «${a.nombre}».`);
      // Su botón ya no existe: el foco vuelve al título de la carpeta
      this.enfocar('titulo');
    });
  }

  private async operar(accion: () => Promise<void>) {
    this.ocupado.set(true);
    this.aviso.set('');
    this.error.set('');
    try {
      await accion();
    } catch (e) {
      this.error.set(mensajeError(e));
    } finally {
      this.ocupado.set(false);
    }
  }

  /** La URL firmada trae Content-Disposition: attachment, así que el navegador descarga sin salir de la página. */
  protected abrir(url: string) {
    this.document.location.assign(url);
  }

  private enfocar(id: string) {
    afterNextRender(() => this.document.getElementById(id)?.focus(), { injector: this.injector });
  }

  protected fecha(segundos: number) {
    return new Date(segundos * 1000).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' });
  }
}
