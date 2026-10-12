package com.mh.historial.domain;

import java.util.Set;

/** Lo que declara el usuario al pedir una subida; solo existe si es válido (reglas en docs/04). */
public record SolicitudSubida(String carpeta, String nombre, String contentType, long size) {

  public static final long TAMANO_MAXIMO = 2L * 1024 * 1024 * 1024;
  private static final Set<String> TIPOS = Set.of("application/pdf", "image/jpeg", "image/png", "video/mp4");

  public SolicitudSubida {
    Carpetas.validar(carpeta);
    // Sin caracteres de control: el nombre irá en Content-Disposition al descargar (Tarea 10)
    if (nombre == null || nombre.isBlank() || nombre.strip().length() > 255
        || nombre.chars().anyMatch(Character::isISOControl)) {
      throw invalido("nombre es obligatorio, tiene máximo 255 caracteres y sin caracteres de control");
    }
    nombre = nombre.strip();
    if (contentType == null || !TIPOS.contains(contentType)) {
      throw invalido("contentType debe ser application/pdf, image/jpeg, image/png o video/mp4");
    }
    if (size < 1 || size > TAMANO_MAXIMO) {
      throw invalido("size debe ser un entero entre 1 byte y 2 GB");
    }
  }

  private static ErrorDeDominio invalido(String mensaje) {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, mensaje);
  }
}
