package com.mh.historial.domain;

import java.util.List;

/** Especialidades fijas (docs/04): no hay tabla ni endpoint de carpetas; el front tiene la misma lista. */
public final class Carpetas {

  public static final List<String> TODAS = List.of("medicina-general", "cardiologia", "dermatologia", "pediatria",
      "ginecologia", "oftalmologia", "traumatologia", "neurologia", "laboratorio", "imagenes", "otros");

  private Carpetas() {}

  public static void validar(String carpeta) {
    // List.of(...).contains(null) lanza NPE
    if (carpeta == null || !TODAS.contains(carpeta)) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, "carpeta no es una de las especialidades permitidas");
    }
  }
}
