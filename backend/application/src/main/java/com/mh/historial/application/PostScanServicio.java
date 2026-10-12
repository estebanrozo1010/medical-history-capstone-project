package com.mh.historial.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caso de uso de mh-postscan (docs/02): escanea el objeto recién subido y deja su registro en CLEAN o REJECTED.
 * La clave S3 {userSub}/{fileId} es la clave primaria de mh-files. Idempotente: solo escanea un PENDING.
 */
public class PostScanServicio {

  private static final Pattern CLAVE =
      Pattern.compile("([^/]+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private final ArchivoRepositorio repositorio;
  private final ArchivoScanner scanner;
  private final AlmacenDeArchivos almacen;

  public PostScanServicio(ArchivoRepositorio repositorio, ArchivoScanner scanner, AlmacenDeArchivos almacen) {
    this.repositorio = repositorio;
    this.scanner = scanner;
    this.almacen = almacen;
  }

  /** Devuelve el resultado para el log: CLEAN, REJECTED, SIN_CAMBIOS (ya no estaba PENDING) o IGNORADO. */
  public String procesar(String clave) {
    Matcher m = CLAVE.matcher(clave == null ? "" : clave);
    if (!m.matches()) {
      return "IGNORADO";
    }
    ArchivoGuardado archivo = repositorio.obtener(m.group(1), m.group(2)).orElse(null);
    if (archivo == null) {
      return "IGNORADO";
    }
    if ("REJECTED".equals(archivo.status())) {
      // Un PUT repetido con la URL aún vigente pudo recrear el objeto después del borrado
      almacen.borrarTodasLasVersiones(clave);
      return "SIN_CAMBIOS";
    }
    if (!"PENDING".equals(archivo.status())) {
      return "SIN_CAMBIOS";
    }
    String estado = scanner.escanear(archivo);
    if (!"CLEAN".equals(estado)) {
      // Borrar antes de marcar: si el borrado falla, el registro sigue PENDING y el reintento lo vuelve a intentar
      almacen.borrarTodasLasVersiones(clave);
    }
    return repositorio.cambiarEstado(archivo.userSub(), archivo.fileId(), "PENDING", estado) ? estado : "SIN_CAMBIOS";
  }
}
