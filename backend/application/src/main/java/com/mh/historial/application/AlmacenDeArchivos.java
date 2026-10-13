package com.mh.historial.application;

import java.time.Duration;
import java.util.List;

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

  /** Inicia una subida en partes con el tipo declarado; devuelve el uploadId. */
  String iniciarMultipart(String clave, String contentType);

  /** URL de UploadPart firmada con el tamaño exacto de la parte. */
  String urlParte(String clave, String uploadId, int partNumber, long size, Duration vigencia);

  /**
   * Une las partes sin reemplazar un objeto existente. ErrorDeDominio: NO_ENCONTRADO si el uploadId no es de la clave,
   * VALIDACION si una parte no coincide con lo subido, CONFLICTO si el objeto ya existe.
   */
  void completarMultipart(String clave, String uploadId, List<Parte> partes);

  /** Descarta las partes subidas; si la subida ya no existe no hace nada. */
  void abortarMultipart(String clave, String uploadId);

  /** Aborta todas las subidas en partes en curso de la clave. */
  void abortarSubidasEnCurso(String clave);
}
