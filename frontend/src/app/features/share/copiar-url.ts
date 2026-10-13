import { Component, DOCUMENT, ElementRef, inject, input, signal, viewChild } from '@angular/core';

/** URL de solo lectura con botón Copiar. */
@Component({
  selector: 'app-copiar-url',
  template: `
    <label [for]="campoId()">{{ etiqueta() }}@if (detalle()) {<span class="sr-only"> de {{ detalle() }}</span>}</label>
    <span class="copiar">
      <input #campo [id]="campoId()" readonly [value]="url()" />
      <button class="secundario" [attr.aria-label]="'Copiar ' + etiqueta().toLowerCase() + (detalle() ? ' de ' + detalle() : '')" (click)="copiar()">Copiar</button>
    </span>
    <p class="ayuda" role="status">{{ aviso() }}</p>
  `,
})
export class CopiarUrl {
  private readonly document = inject(DOCUMENT);
  readonly url = input.required<string>();
  readonly campoId = input.required<string>();
  readonly etiqueta = input('Enlace para compartir');
  /** Solo para lectores de pantalla: distingue varios campos en una lista. */
  readonly detalle = input('');
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  protected readonly aviso = signal('');

  /** La Clipboard API solo existe en HTTPS o localhost; el sitio de S3 de la PoC es HTTP, de ahí la alternativa. */
  protected async copiar() {
    try {
      await navigator.clipboard.writeText(this.url());
      this.aviso.set('Enlace copiado.');
      return;
    } catch {
      // Sin Clipboard API o sin permiso: se intenta con la selección
    }
    this.campo().nativeElement.select();
    let copiado = false;
    try {
      copiado = this.document.execCommand('copy');
    } catch {
      // Navegador sin execCommand: queda la selección para Ctrl+C
    }
    this.aviso.set(copiado ? 'Enlace copiado.' : 'No se pudo copiar. El enlace quedó seleccionado: cópialo con Ctrl+C.');
  }
}
