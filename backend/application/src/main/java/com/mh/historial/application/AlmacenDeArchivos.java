package com.mh.historial.application;

import java.time.Duration;

/** Puerto de salida hacia el bucket de documentos. */
public interface AlmacenDeArchivos {

  /** URL de PUT firmada con el tipo y el tamaño exactos: S3 rechaza un cuerpo de otro tamaño. */
  UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia);

  /** URL de GET firmada; S3 responde con este Content-Disposition. */
  String urlDescarga(String clave, String contentDisposition, Duration vigencia);

  /** Hasta `cantidad` bytes del inicio del objeto (lectura por rango); vacío si el objeto no existe. */
  byte[] primerosBytes(String clave, int cantidad);

  /**
   * Borra todas las versiones del objeto en el bucket de documentos y en el de respaldo; sin objeto no hace nada.
   * IllegalStateException sin borrar nada si la réplica al respaldo sigue en curso al agotar la espera.
   */
  void borrarTodasLasVersiones(String clave);
}
