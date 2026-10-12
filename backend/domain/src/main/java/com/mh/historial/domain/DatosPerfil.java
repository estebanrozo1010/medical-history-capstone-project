package com.mh.historial.domain;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;

/** Datos del perfil que envía el usuario; solo existe si son válidos (reglas en docs/04). */
public record DatosPerfil(String nombre, String cedula, LocalDate fechaNacimiento, String genero) {

  private static final LocalDate FECHA_MINIMA = LocalDate.of(1900, 1, 1);
  private static final Set<String> GENEROS = Set.of("M", "F", "O");
  // STRICT rechaza fechas inexistentes como 1990-02-30 (uuuu: año sin era)
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  public DatosPerfil {
    if (nombre == null || nombre.isBlank() || nombre.strip().length() > 100) {
      throw invalido("nombre es obligatorio y tiene máximo 100 caracteres");
    }
    nombre = nombre.strip();
    if (cedula == null || !cedula.matches("\\d{5,15}")) {
      throw invalido("cedula debe tener de 5 a 15 dígitos");
    }
    if (fechaNacimiento == null || fechaNacimiento.isBefore(FECHA_MINIMA)
        || fechaNacimiento.isAfter(LocalDate.now(ZoneOffset.UTC))) {
      throw invalido("fechaNacimiento debe estar entre 1900-01-01 y hoy");
    }
    if (genero == null || !GENEROS.contains(genero)) {
      throw invalido("genero debe ser M, F u O");
    }
  }

  /** Construye desde los textos del body; la fecha va en formato AAAA-MM-DD. */
  public static DatosPerfil de(String nombre, String cedula, String fechaNacimiento, String genero) {
    if (fechaNacimiento == null) {
      throw invalido("fechaNacimiento es obligatoria");
    }
    try {
      return new DatosPerfil(nombre, cedula, LocalDate.parse(fechaNacimiento, ISO), genero);
    } catch (DateTimeParseException e) {
      throw invalido("fechaNacimiento debe tener formato AAAA-MM-DD");
    }
  }

  private static ErrorDeDominio invalido(String mensaje) {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, mensaje);
  }
}
