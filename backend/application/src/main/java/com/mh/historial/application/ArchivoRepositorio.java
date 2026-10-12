package com.mh.historial.application;

import java.util.List;

/** Puerto de salida hacia mh-files. */
public interface ArchivoRepositorio {

  void crear(ArchivoGuardado archivo);

  /** Todos los archivos del usuario, o solo los de una carpeta si no es null. */
  List<ArchivoGuardado> listar(String userSub, String carpeta);
}
