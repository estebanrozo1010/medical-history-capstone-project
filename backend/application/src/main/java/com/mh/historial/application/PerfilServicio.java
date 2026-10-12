package com.mh.historial.application;

import com.mh.historial.domain.DatosPerfil;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.Perfil;
import java.time.Clock;
import java.time.LocalDate;

/** Casos de uso de mh-profile: RegistrarPerfil, ObtenerPerfil y ActualizarPerfil (docs/02). */
public class PerfilServicio {

  private final PerfilRepositorio repositorio;
  private final Cifrador cifrador;
  private final Clock reloj;

  public PerfilServicio(PerfilRepositorio repositorio, Cifrador cifrador, Clock reloj) {
    this.repositorio = repositorio;
    this.cifrador = cifrador;
    this.reloj = reloj;
  }

  /** sub y email salen del ID token, nunca del body. */
  public Perfil registrar(String sub, String email, DatosPerfil datos) {
    repositorio.crear(cifrado(sub, email, datos));
    return new Perfil(email, datos);
  }

  public Perfil obtener(String sub) {
    PerfilGuardado g = repositorio.buscar(sub)
        .orElseThrow(() -> new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "El perfil no existe"));
    return new Perfil(g.email(), new DatosPerfil(g.nombre(),
        cifrador.descifrar(g.cedulaEnc(), sub),
        LocalDate.parse(cifrador.descifrar(g.fechaNacimientoEnc(), sub)),
        g.genero()));
  }

  public Perfil actualizar(String sub, String email, DatosPerfil datos) {
    repositorio.actualizar(cifrado(sub, email, datos));
    return new Perfil(email, datos);
  }

  private PerfilGuardado cifrado(String sub, String email, DatosPerfil datos) {
    long ahora = reloj.instant().getEpochSecond();
    return new PerfilGuardado(sub, email, datos.nombre(),
        cifrador.cifrar(datos.cedula(), sub),
        cifrador.cifrar(datos.fechaNacimiento().toString(), sub),
        datos.genero(), ahora, ahora);
  }
}
