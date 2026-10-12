package com.mh.historial.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatosPerfilTest {

  @Test
  void aceptaDatosValidosYRecortaElNombre() {
    DatosPerfil d = DatosPerfil.de("  Ana Pérez ", "1020304050", "1990-05-17", "F");

    assertEquals("Ana Pérez", d.nombre());
    assertEquals("1020304050", d.cedula());
    assertEquals(LocalDate.of(1990, 5, 17), d.fechaNacimiento());
    assertEquals("F", d.genero());
  }

  @ParameterizedTest
  @CsvSource(nullValues = "null", value = {
      "null, 1020304050, 1990-05-17, F",       // nombre obligatorio
      "'   ', 1020304050, 1990-05-17, F",      // nombre en blanco
      "Ana, null, 1990-05-17, F",              // cédula obligatoria
      "Ana, 1234, 1990-05-17, F",              // cédula corta
      "Ana, 1234567890123456, 1990-05-17, F",  // cédula larga
      "Ana, 12.345.678, 1990-05-17, F",        // cédula con puntos
      "Ana, 1020304050, null, F",              // fecha obligatoria
      "Ana, 1020304050, 17/05/1990, F",        // formato de fecha
      "Ana, 1020304050, 1990-02-30, F",        // fecha inexistente
      "Ana, 1020304050, 1899-12-31, F",        // antes de 1900
      "Ana, 1020304050, 1990-05-17, X",        // género fuera de la lista
      "Ana, 1020304050, 1990-05-17, null",     // género obligatorio
  })
  void rechazaDatosInvalidos(String nombre, String cedula, String fecha, String genero) {
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> DatosPerfil.de(nombre, cedula, fecha, genero));
    assertEquals(ErrorDeDominio.Tipo.VALIDACION, e.tipo());
  }

  @Test
  void rechazaNombreDeMasDe100Caracteres() {
    assertThrows(ErrorDeDominio.class, () -> DatosPerfil.de("a".repeat(101), "1020304050", "1990-05-17", "M"));
    DatosPerfil.de("a".repeat(100), "1020304050", "1990-05-17", "M");
  }

  @Test
  void rechazaFechaFuturaYAceptaHoy() {
    LocalDate hoy = LocalDate.now(ZoneOffset.UTC);
    assertThrows(ErrorDeDominio.class, () -> DatosPerfil.de("Ana", "1020304050", hoy.plusDays(1).toString(), "O"));
    DatosPerfil.de("Ana", "1020304050", hoy.toString(), "O");
  }
}
