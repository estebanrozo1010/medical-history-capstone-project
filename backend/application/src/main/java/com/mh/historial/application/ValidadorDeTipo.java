package com.mh.historial.application;

import java.util.Arrays;
import java.util.Map;

/**
 * Escáner de la PoC (GuardDuty no es viable en el Learner Lab): el contenido debe empezar con la firma del tipo
 * declarado. No detecta malware dentro de un PDF válido; solo rechaza lo que no es lo que dice ser.
 */
public class ValidadorDeTipo implements ArchivoScanner {

  private static final int BYTES_A_LEER = 12;
  private static final Map<String, byte[]> FIRMAS = Map.of(
      "application/pdf", new byte[] {'%', 'P', 'D', 'F'},
      "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
      "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
  // MP4 (ISO BMFF): los 4 primeros bytes son el tamaño de la caja; "ftyp" va en el offset 4
  private static final byte[] FTYP = {'f', 't', 'y', 'p'};

  private final AlmacenDeArchivos almacen;

  public ValidadorDeTipo(AlmacenDeArchivos almacen) {
    this.almacen = almacen;
  }

  @Override
  public String escanear(ArchivoGuardado archivo) {
    byte[] inicio = almacen.primerosBytes(archivo.s3Key(), BYTES_A_LEER);
    return coincide(archivo.contentType(), inicio) ? "CLEAN" : "REJECTED";
  }

  private static boolean coincide(String contentType, byte[] inicio) {
    if ("video/mp4".equals(contentType)) {
      return inicio.length >= 8 && Arrays.equals(inicio, 4, 8, FTYP, 0, 4);
    }
    byte[] firma = FIRMAS.get(contentType);
    return firma != null && inicio.length >= firma.length && Arrays.equals(inicio, 0, firma.length, firma, 0, firma.length);
  }
}
