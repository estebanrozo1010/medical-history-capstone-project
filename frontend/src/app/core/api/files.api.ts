import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Config } from '../config';

/** Lista fija de especialidades (docs/04); el backend rechaza cualquier otra. */
export const CARPETAS = [
  { id: 'medicina-general', nombre: 'Medicina general' },
  { id: 'cardiologia', nombre: 'Cardiología' },
  { id: 'dermatologia', nombre: 'Dermatología' },
  { id: 'pediatria', nombre: 'Pediatría' },
  { id: 'ginecologia', nombre: 'Ginecología' },
  { id: 'oftalmologia', nombre: 'Oftalmología' },
  { id: 'traumatologia', nombre: 'Traumatología' },
  { id: 'neurologia', nombre: 'Neurología' },
  { id: 'laboratorio', nombre: 'Laboratorio' },
  { id: 'imagenes', nombre: 'Imágenes' },
  { id: 'otros', nombre: 'Otros' },
] as const;

export const TIPOS_PERMITIDOS = ['application/pdf', 'image/jpeg', 'image/png', 'video/mp4'];
export const TAMANO_MAXIMO = 2 * 1024 ** 3;

/** Misma regla que el backend (que vuelve a validar); null si el archivo se puede subir. */
export const validarArchivo = (f: File): string | null =>
  !TIPOS_PERMITIDOS.includes(f.type) ? 'El archivo debe ser PDF, JPG, PNG o MP4.'
  : f.size === 0 ? 'El archivo está vacío.'
  : f.size > TAMANO_MAXIMO ? 'El archivo supera el máximo de 2 GB.'
  : null;

/** Elemento de GET /files; status es el efectivo que calcula el backend. */
export interface Archivo {
  fileId: string;
  carpeta: string;
  nombre: string;
  contentType: string;
  size: number;
  status: 'PENDING' | 'CLEAN' | 'INFECTED' | 'REJECTED' | 'SCAN_FAILED' | 'UPLOAD_EXPIRED';
  createdAt: number;
}

/** Respuesta de POST /files/upload-url: PUT firmado de 10 min. */
export interface SubidaAutorizada {
  fileId: string;
  url: string;
  headers: Record<string, string>;
  expiresIn: number;
}

@Injectable({ providedIn: 'root' })
export class FilesApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(Config);
  private get url() {
    return `${this.config.value.apiUrl}/files`;
  }

  listar(carpeta?: string) {
    return this.http.get<Archivo[]>(this.url, { params: carpeta ? { carpeta } : {} });
  }

  pedirSubida(carpeta: string, archivo: File) {
    return this.http.post<SubidaAutorizada>(`${this.url}/upload-url`, {
      carpeta, nombre: archivo.name, contentType: archivo.type, size: archivo.size,
    });
  }

  /** PUT directo a S3. La URL no empieza con apiUrl, así que el interceptor no le pone el token. */
  subirAS3(subida: SubidaAutorizada, archivo: File) {
    return this.http.put(subida.url, archivo, {
      headers: subida.headers, reportProgress: true, observe: 'events', responseType: 'text',
    });
  }
}
