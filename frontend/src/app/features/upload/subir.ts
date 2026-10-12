import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, concatMap, filter, firstValueFrom, lastValueFrom, map, of, take, tap, timer } from 'rxjs';
import { Archivo, CARPETAS, FilesApi, TIPOS_PERMITIDOS, validarArchivo } from '../../core/api/files.api';
import { ESTADOS } from '../folders/carpeta';

// Tras subir, GET /files?carpeta= cada 3 s hasta que deje de estar PENDING, máximo 2 min (docs/03)
const CONSULTA_MS = 3000;
const CONSULTAS = 40;

const MOTIVOS: Partial<Record<Archivo['status'], string>> = {
  REJECTED: 'el contenido no corresponde al tipo de archivo declarado.',
  INFECTED: 'se detectó malware en el archivo.',
  SCAN_FAILED: 'no se pudo revisar el archivo.',
  UPLOAD_EXPIRED: 'la subida no se completó a tiempo.',
};

/** Subida simple: pide la URL firmada, hace el PUT directo a S3 con progreso y espera el escaneo (docs/03). */
@Component({
  selector: 'app-subir',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Subir archivo</h1>
    <form [formGroup]="form" (ngSubmit)="subir()" novalidate>
      <div class="campo">
        <label for="carpeta">Carpeta</label>
        <select id="carpeta" formControlName="carpeta"
          [attr.aria-invalid]="carpeta.invalid && carpeta.touched" aria-describedby="carpeta-error">
          <option value="" disabled>Selecciona una carpeta</option>
          @for (c of carpetas; track c.id) {
            <option [value]="c.id">{{ c.nombre }}</option>
          }
        </select>
        @if (carpeta.invalid && carpeta.touched) {
          <p id="carpeta-error" class="error">Selecciona una carpeta.</p>
        }
      </div>
      <div class="campo">
        <label for="archivo">Archivo</label>
        <input id="archivo" type="file" [accept]="tipos" (change)="elegir($event)"
          [attr.aria-invalid]="!!errorArchivo()" aria-describedby="archivo-ayuda archivo-error" />
        <p id="archivo-ayuda" class="ayuda">PDF, JPG, PNG o MP4, hasta 2 GB.</p>
        @if (errorArchivo()) {
          <p id="archivo-error" class="error">{{ errorArchivo() }}</p>
        }
      </div>
      @if (progreso() !== null) {
        <div class="campo">
          <label for="progreso">Progreso de la subida: {{ progreso() }} %</label>
          <progress id="progreso" max="100" [value]="progreso()"></progress>
        </div>
      }
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      @if (subido(); as s) {
        <p [class]="s.estado && motivos[s.estado] ? 'error' : 'exito'" role="status">
          @if (s.estado && motivos[s.estado]) {
            Subida fallida, vuelve a intentar. «{{ s.nombre }}» quedó {{ estados[s.estado] }}: {{ motivos[s.estado] }}
          } @else if (s.estado) {
            «{{ s.nombre }}» se subió. Estado: {{ estados[s.estado] }}{{ s.estado === 'PENDING' ? ' (revisando el archivo…)' : '.' }}
          } @else {
            «{{ s.nombre }}» se subió, pero la revisión está tardando. Mira su estado más tarde en la carpeta.
          }
          <a [routerLink]="['/carpetas', s.carpeta]">Ver la carpeta</a>
        </p>
      }
      <button type="submit" [disabled]="subiendo()">{{ subiendo() ? (progreso() === 100 ? 'Revisando…' : 'Subiendo…') : 'Subir' }}</button>
    </form>
  `,
})
export class Subir {
  private readonly api = inject(FilesApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly carpetas = CARPETAS;
  protected readonly tipos = TIPOS_PERMITIDOS.join(',');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    carpeta: [inject(ActivatedRoute).snapshot.queryParamMap.get('carpeta') ?? '', Validators.required],
  });
  protected readonly carpeta = this.form.controls.carpeta;
  private readonly archivo = signal<File | null>(null);
  protected readonly errorArchivo = signal('');
  protected readonly progreso = signal<number | null>(null);
  protected readonly subiendo = signal(false);
  protected readonly error = signal('');
  // estado null: se agotaron las consultas sin salir de PENDING
  protected readonly subido = signal<{ nombre: string; carpeta: string; estado: Archivo['status'] | null } | null>(null);
  protected readonly estados = ESTADOS;
  protected readonly motivos = MOTIVOS;

  protected elegir(evento: Event) {
    const archivo = (evento.target as HTMLInputElement).files?.[0] ?? null;
    this.archivo.set(archivo);
    this.errorArchivo.set(archivo ? (validarArchivo(archivo) ?? '') : '');
  }

  protected async subir() {
    this.carpeta.markAsTouched();
    const archivo = this.archivo();
    this.errorArchivo.set(archivo ? (validarArchivo(archivo) ?? '') : 'Elige un archivo.');
    if (!archivo || this.carpeta.invalid || this.errorArchivo()) return;
    const carpeta = this.carpeta.value;
    this.subiendo.set(true);
    this.error.set('');
    this.subido.set(null);
    this.progreso.set(0);
    try {
      const subida = await firstValueFrom(this.api.pedirSubida(carpeta, archivo));
      await lastValueFrom(
        this.api.subirAS3(subida, archivo).pipe(
          tap((e) => {
            if (e.type === HttpEventType.UploadProgress && e.total) this.progreso.set(Math.round((100 * e.loaded) / e.total));
          }),
          // Salir de la pantalla cancela el PUT
          takeUntilDestroyed(this.destroyRef),
        ),
      );
      this.progreso.set(100);
      this.subido.set({ nombre: archivo.name, carpeta, estado: 'PENDING' });
      const estado = await this.esperarEscaneo(subida.fileId, carpeta);
      this.subido.set({ nombre: archivo.name, carpeta, estado });
    } catch (e) {
      const http = e instanceof HttpErrorResponse ? e : null;
      this.progreso.set(null);
      // Solo los 400 de la API traen {code, message}; los errores de S3 son XML
      this.error.set(
        http?.status === 400 && http.error?.message ? http.error.message : 'No se pudo subir el archivo. Inténtalo de nuevo.',
      );
    } finally {
      this.subiendo.set(false);
    }
  }

  /** Estado final del escaneo, o null si sigue PENDING tras 2 min (o si se sale de la pantalla). */
  private esperarEscaneo(fileId: string, carpeta: string) {
    return firstValueFrom(
      timer(CONSULTA_MS, CONSULTA_MS).pipe(
        take(CONSULTAS),
        // Un fallo de red en una consulta no corta la espera
        concatMap(() => this.api.listar(carpeta).pipe(catchError(() => of<Archivo[]>([])))),
        map((archivos) => archivos.find((a) => a.fileId === fileId)?.status),
        filter((s): s is Archivo['status'] => !!s && s !== 'PENDING'),
        takeUntilDestroyed(this.destroyRef),
      ),
      { defaultValue: null },
    );
  }
}
