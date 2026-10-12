import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Config } from '../config';

/** Body de POST/PUT /profile (docs/04). */
export interface DatosPerfil {
  nombre: string;
  cedula: string;
  fechaNacimiento: string;
  genero: 'M' | 'F' | 'O';
}

/** Respuesta de /profile: el email sale del token, no del formulario. */
export interface PerfilUsuario extends DatosPerfil {
  email: string;
}

@Injectable({ providedIn: 'root' })
export class ProfileApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(Config);
  private get url() {
    return `${this.config.value.apiUrl}/profile`;
  }

  get() {
    return this.http.get<PerfilUsuario>(this.url);
  }

  create(datos: DatosPerfil) {
    return this.http.post<PerfilUsuario>(this.url, datos);
  }

  update(datos: DatosPerfil) {
    return this.http.put<PerfilUsuario>(this.url, datos);
  }
}
