package com.mh.historial.application;

import java.util.List;
import java.util.Optional;

/** Puerto de salida hacia mh-files. */
public interface ArchivoRepositorio {

  void crear(ArchivoGuardado archivo);

  /** Todos los archivos del usuario, o solo los de una carpeta si no es null. */
  List<ArchivoGuardado> listar(String userSub, String carpeta);

  Optional<ArchivoGuardado> obtener(String userSub, String fileId);

  /** Cambia el status solo si sigue siendo `de`; false si el registro no existe o ya cambió. */
  boolean cambiarEstado(String userSub, String fileId, String de, String a);

  void eliminar(String userSub, String fileId);
}
