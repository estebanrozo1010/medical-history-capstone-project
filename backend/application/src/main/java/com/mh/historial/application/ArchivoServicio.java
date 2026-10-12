package com.mh.historial.application;

import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.Carpetas;
import com.mh.historial.domain.SolicitudSubida;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Casos de uso de mh-files: SolicitarSubida y ListarArchivos (docs/02). El userSub sale del ID token. */
public class ArchivoServicio {

  static final Duration VIGENCIA_URL = Duration.ofMinutes(10);
  static final long VIGENCIA_PENDIENTE = Duration.ofHours(1).toSeconds();

  private final ArchivoRepositorio repositorio;
  private final AlmacenDeArchivos almacen;
  private final Clock reloj;

  public ArchivoServicio(ArchivoRepositorio repositorio, AlmacenDeArchivos almacen, Clock reloj) {
    this.repositorio = repositorio;
    this.almacen = almacen;
    this.reloj = reloj;
  }

  public SubidaAutorizada solicitarSubida(String userSub, SolicitudSubida s) {
    String fileId = UUID.randomUUID().toString();
    String clave = userSub + "/" + fileId;
    // Primero el registro: nunca queda un objeto en S3 sin su fila en mh-files
    repositorio.crear(new ArchivoGuardado(userSub, fileId, s.carpeta(), s.nombre(), s.contentType(), s.size(),
        clave, "PENDING", reloj.instant().getEpochSecond()));
    UrlSubida u = almacen.urlSubida(clave, s.contentType(), s.size(), VIGENCIA_URL);
    return new SubidaAutorizada(fileId, u.url(), u.headers(), VIGENCIA_URL.toSeconds());
  }

  /** carpeta null = todas. Del más nuevo al más viejo. */
  public List<Archivo> listar(String userSub, String carpeta) {
    if (carpeta != null) {
      Carpetas.validar(carpeta);
    }
    long ahora = reloj.instant().getEpochSecond();
    return repositorio.listar(userSub, carpeta).stream()
        .sorted(Comparator.comparingLong(ArchivoGuardado::createdAt).reversed())
        .map(g -> new Archivo(g.fileId(), g.carpeta(), g.nombre(), g.contentType(), g.size(),
            estadoEfectivo(g, ahora), g.createdAt()))
        .toList();
  }

  /** Calculado al leer, no se guarda (docs/04). */
  private static String estadoEfectivo(ArchivoGuardado g, long ahora) {
    return "PENDING".equals(g.status()) && ahora - g.createdAt() > VIGENCIA_PENDIENTE ? "UPLOAD_EXPIRED" : g.status();
  }
}
