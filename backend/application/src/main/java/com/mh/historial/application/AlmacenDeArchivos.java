package com.mh.historial.application;

import java.time.Duration;

/** Puerto de salida hacia el bucket de documentos. */
public interface AlmacenDeArchivos {

  /** URL de PUT firmada con el tipo y el tamaño exactos: S3 rechaza un cuerpo de otro tamaño. */
  UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia);
}
