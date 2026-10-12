package com.mh.historial.domain;

/** Error de negocio; el adaptador de entrada lo traduce a HTTP (docs/04). */
public class ErrorDeDominio extends RuntimeException {

  public enum Tipo { VALIDACION, NO_ENCONTRADO, CONFLICTO }

  private final Tipo tipo;

  public ErrorDeDominio(Tipo tipo, String mensaje) {
    super(mensaje);
    this.tipo = tipo;
  }

  public Tipo tipo() {
    return tipo;
  }
}
