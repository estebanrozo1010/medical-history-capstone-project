import { HttpClient } from '@angular/common/http';
import { Injectable, inject, linkedSignal } from '@angular/core';
import { map, tap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { Config } from '../config';

export const MAX_ARCHIVOS_POR_LINK = 50;

export interface ArchivoDeLink {
  fileId: string;
  nombre: string;
}

/** Elemento de GET /links; archivos sin orden garantizado (fileIds es un SS). */
export interface Link {
  token: string;
  archivos: ArchivoDeLink[];
  activo: boolean;
  expiresAt: number;
}

/** Respuesta de POST /links: url = {origen del front}/compartido/{token}. */
export interface LinkCreado {
  token: string;
  url: string;
  expiresAt: number;
}

@Injectable({ providedIn: 'root' })
export class LinksApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(Config);
  /**
   * GET /links lee un GSI (eventual): un link recién creado puede no venir todavía.
   * Se recuerdan los creados en la sesión hasta que el backend los devuelva; se vacía al cambiar de usuario.
   */
  private readonly recientes = linkedSignal({ source: inject(AuthService).email, computation: (): Link[] => [] });
  private get url() {
    return `${this.config.value.apiUrl}/links`;
  }

  crear(archivos: ArchivoDeLink[], ttlMinutos: number) {
    return this.http.post<LinkCreado>(this.url, { fileIds: archivos.map((a) => a.fileId), ttlMinutos }).pipe(
      tap((c) => this.recientes.update((l) => [{ token: c.token, archivos, activo: true, expiresAt: c.expiresAt }, ...l])),
    );
  }

  listar() {
    return this.http.get<Link[]>(this.url).pipe(
      map((lista) => {
        const ahora = Date.now() / 1000;
        this.recientes.update((l) => l.filter((r) => r.expiresAt > ahora && !lista.some((x) => x.token === r.token)));
        return [...this.recientes(), ...lista];
      }),
    );
  }

  /** 404 si es ajeno, no existe o ya venció. */
  cambiarEstado(token: string, activo: boolean) {
    return this.http.patch<{ token: string; activo: boolean; expiresAt: number }>(`${this.url}/${encodeURIComponent(token)}`, { activo }).pipe(
      tap((r) => this.recientes.update((l) => l.map((x) => (x.token === token ? { ...x, activo: r.activo } : x)))),
    );
  }
}
