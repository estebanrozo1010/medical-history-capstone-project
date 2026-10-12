package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mh.historial.application.ArchivoServicioTest.AlmacenFalso;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ValidadorDeTipoTest {

  static final byte[] PDF = "%PDF-1.7\n%".getBytes(StandardCharsets.ISO_8859_1);
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
  static final byte[] MP4 = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
  static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0};
  static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR".getBytes(StandardCharsets.ISO_8859_1);

  final AlmacenFalso almacen = new AlmacenFalso();
  final ValidadorDeTipo validador = new ValidadorDeTipo(almacen);

  @Test
  void cadaTipoPermitidoPasaConSusPrimerosBytes() {
    assertEquals("CLEAN", escanear("application/pdf", PDF));
    assertEquals("CLEAN", escanear("image/png", PNG));
    assertEquals("CLEAN", escanear("image/jpeg", JPEG));
    assertEquals("CLEAN", escanear("video/mp4", MP4));
  }

  @Test
  void unExeOEicarDeclaradoComoPdfSeRechaza() {
    assertEquals("REJECTED", escanear("application/pdf", EXE));
    assertEquals("REJECTED", escanear("application/pdf", EICAR));
  }

  @Test
  void bytesDeOtroTipoPermitidoTambienSeRechazan() {
    assertEquals("REJECTED", escanear("application/pdf", PNG));
    assertEquals("REJECTED", escanear("image/png", JPEG));
    assertEquals("REJECTED", escanear("video/mp4", PDF));
  }

  @Test
  void objetoVacioCortoOInexistenteSeRechaza() {
    assertEquals("REJECTED", escanear("application/pdf", new byte[0]));
    assertEquals("REJECTED", escanear("image/png", new byte[] {(byte) 0x89, 'P', 'N'}));
    assertEquals("REJECTED", escanear("video/mp4", new byte[] {0, 0, 0, 0x20, 'f', 't'}));
  }

  @Test
  void leeSoloLosPrimerosBytesDelObjeto() {
    escanear("application/pdf", PDF);

    assertEquals("sub-a/f1|12", almacen.leido);
  }

  private String escanear(String contentType, byte[] contenido) {
    almacen.contenido = contenido;
    return validador.escanear(new ArchivoGuardado("sub-a", "f1", "otros", "a", contentType, 100, "sub-a/f1", "PENDING", 0));
  }
}
