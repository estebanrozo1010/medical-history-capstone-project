import { Injectable } from '@angular/core';

export interface AppConfig {
  apiUrl: string;
  userPoolId: string;
  clientId: string;
  region: string;
}

/** Lee public/config.json en el arranque (no va compilado: el mismo build sirve para cualquier entorno). */
@Injectable({ providedIn: 'root' })
export class Config {
  value: AppConfig = { apiUrl: '', userPoolId: '', clientId: '', region: '' };

  async load(): Promise<void> {
    const res = await fetch('config.json', { cache: 'no-cache' });
    if (!res.ok) throw new Error(`No se pudo cargar config.json (${res.status})`);
    this.value = await res.json();
  }
}
