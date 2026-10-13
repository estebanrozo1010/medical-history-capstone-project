package com.mh.historial.application;

import java.util.List;
import java.util.Optional;

/** Puerto de salida hacia mh-links. */
public interface LinkRepositorio {

  void crear(LinkGuardado link);

  /** Por el GSI byUser, en cualquier orden. Lectura eventual: un link recién creado puede tardar en aparecer. */
  List<LinkGuardado> listar(String userSub);

  Optional<LinkGuardado> obtener(String token);

  /** false si el link ya no existe (p. ej. lo borró el TTL). */
  boolean cambiarActivo(String token, boolean activo);
}
