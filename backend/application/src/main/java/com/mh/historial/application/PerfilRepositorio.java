package com.mh.historial.application;

import java.util.Optional;

/** Puerto de salida hacia mh-profiles. */
public interface PerfilRepositorio {

  Optional<PerfilGuardado> buscar(String sub);

  /** Lanza ErrorDeDominio CONFLICTO si ya hay perfil para ese sub. */
  void crear(PerfilGuardado perfil);

  /** Conserva createdAt; lanza ErrorDeDominio NO_ENCONTRADO si no hay perfil. */
  void actualizar(PerfilGuardado perfil);
}
