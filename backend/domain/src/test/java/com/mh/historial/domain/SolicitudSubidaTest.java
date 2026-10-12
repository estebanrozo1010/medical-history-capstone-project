package com.mh.historial.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SolicitudSubidaTest {

  static final long DOS_GB = 2L * 1024 * 1024 * 1024;

  @Test
  void aceptaUnaSolicitudValidaYRecortaElNombre() {
    SolicitudSubida s = new SolicitudSubida("cardiologia", "  examen.pdf ", "application/pdf", 1000);

    assertEquals("cardiologia", s.carpeta());
    assertEquals("examen.pdf", s.nombre());
    assertEquals("application/pdf", s.contentType());
    assertEquals(1000, s.size());
  }

  @Test
  void aceptaLosLimitesDeTamano() {
    assertEquals(1, new SolicitudSubida("otros", "a.png", "image/png", 1).size());
    assertEquals(DOS_GB, new SolicitudSubida("imagenes", "v.mp4", "video/mp4", DOS_GB).size());
  }

  @ParameterizedTest
  @CsvSource(nullValues = "null", value = {
      "null, a.pdf, application/pdf, 10",            // carpeta obligatoria
      "Cardiologia, a.pdf, application/pdf, 10",     // carpeta fuera de la lista
      "../otros, a.pdf, application/pdf, 10",        // carpeta inventada
      "otros, null, application/pdf, 10",            // nombre obligatorio
      "otros, '  ', application/pdf, 10",            // nombre en blanco
      "otros, a.pdf, null, 10",                      // tipo obligatorio
      "otros, a.exe, application/x-msdownload, 10",  // tipo no permitido
      "otros, a.gif, image/gif, 10",                 // tipo no permitido
      "otros, a.pdf, application/pdf, 0",            // vacío
      "otros, a.pdf, application/pdf, -1",           // negativo
      "otros, a.pdf, application/pdf, 2147483649",   // 2 GB + 1
  })
  void rechazaSolicitudesInvalidas(String carpeta, String nombre, String tipo, long size) {
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> new SolicitudSubida(carpeta, nombre, tipo, size));
    assertEquals(ErrorDeDominio.Tipo.VALIDACION, e.tipo());
  }

  @Test
  void rechazaNombresLargosOConCaracteresDeControl() {
    assertThrows(ErrorDeDominio.class, () -> new SolicitudSubida("otros", "a".repeat(256), "application/pdf", 1));
    assertThrows(ErrorDeDominio.class, () -> new SolicitudSubida("otros", "a\r\nb.pdf", "application/pdf", 1));
    assertEquals(255, new SolicitudSubida("otros", "a".repeat(255), "application/pdf", 1).nombre().length());
  }

  @Test
  void laListaDeCarpetasEsLaDeDocs04() {
    assertEquals(11, Carpetas.TODAS.size());
    Carpetas.TODAS.forEach(Carpetas::validar);
    assertThrows(ErrorDeDominio.class, () -> Carpetas.validar("compartidos"));
  }
}
