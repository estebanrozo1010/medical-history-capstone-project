import { Injectable, computed, inject, linkedSignal } from '@angular/core';
import { ArchivoDeLink, MAX_ARCHIVOS_POR_LINK } from '../../core/api/links.api';
import { AuthService } from '../../core/auth/auth.service';

/** Archivos CLEAN elegidos para un link; se conserva al cambiar de carpeta y se vacía al cambiar de usuario. */
@Injectable({ providedIn: 'root' })
export class Seleccion {
  readonly archivos = linkedSignal({ source: inject(AuthService).email, computation: (): ArchivoDeLink[] => [] });
  readonly llena = computed(() => this.archivos().length >= MAX_ARCHIVOS_POR_LINK);

  tiene(fileId: string) {
    return this.archivos().some((a) => a.fileId === fileId);
  }

  alternar(a: ArchivoDeLink) {
    if (this.tiene(a.fileId)) this.quitar([a.fileId]);
    else this.agregar([a]);
  }

  /** Agrega los que falten, sin pasar del máximo por link. */
  agregar(lista: ArchivoDeLink[]) {
    this.archivos.update((s) => [...s, ...lista.filter((a) => !s.some((x) => x.fileId === a.fileId))
      .map(({ fileId, nombre }) => ({ fileId, nombre }))].slice(0, MAX_ARCHIVOS_POR_LINK));
  }

  quitar(fileIds: string[]) {
    this.archivos.update((s) => s.filter((a) => !fileIds.includes(a.fileId)));
  }

  limpiar() {
    this.archivos.set([]);
  }
}
