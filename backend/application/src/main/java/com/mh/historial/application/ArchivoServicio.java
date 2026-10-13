package com.mh.historial.application;

import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.Carpetas;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.SolicitudSubida;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Casos de uso de mh-files (docs/02). El userSub sale del ID token: un fileId ajeno no se encuentra. */
public class ArchivoServicio {

  static final Duration VIGENCIA_URL = Duration.ofMinutes(10);
  static final long VIGENCIA_PENDIENTE = Duration.ofHours(1).toSeconds();
  public static final Duration VIGENCIA_DESCARGA = Duration.ofMinutes(5);
  static final long TAMANO_PARTE = 16L * 1024 * 1024;

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

  /** Archivos grandes (docs/03): mismas validaciones y el registro también antes de iniciar la subida en S3. */
  public SubidaMultipart iniciarMultipart(String userSub, SolicitudSubida s) {
    String fileId = UUID.randomUUID().toString();
    String clave = userSub + "/" + fileId;
    repositorio.crear(new ArchivoGuardado(userSub, fileId, s.carpeta(), s.nombre(), s.contentType(), s.size(),
        clave, "PENDING", reloj.instant().getEpochSecond()));
    return new SubidaMultipart(fileId, almacen.iniciarMultipart(clave, s.contentType()), TAMANO_PARTE);
  }

  /** Cada parte va firmada con su tamaño exacto: la suma no puede pasar del tamaño declarado. */
  public String urlParte(String userSub, String fileId, String uploadId, int partNumber) {
    ArchivoGuardado g = pendiente(userSub, fileId);
    long partes = partes(g);
    if (partNumber < 1 || partNumber > partes) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, "partNumber debe estar entre 1 y " + partes);
    }
    long size = partNumber < partes ? TAMANO_PARTE : g.size() - (partes - 1) * TAMANO_PARTE;
    return almacen.urlParte(g.s3Key(), uploadId, partNumber, size, VIGENCIA_URL);
  }

  public void completarMultipart(String userSub, String fileId, String uploadId, List<Parte> partes) {
    ArchivoGuardado g = pendiente(userSub, fileId);
    boolean completas = partes.size() == partes(g) && IntStream.range(0, partes.size()).allMatch(i ->
        partes.get(i).partNumber() == i + 1 && partes.get(i).etag() != null && !partes.get(i).etag().isBlank());
    if (!completas) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION,
          "parts debe traer las " + partes(g) + " partes en orden, cada una con su etag");
    }
    almacen.completarMultipart(g.s3Key(), uploadId, partes);
  }

  public void abortarMultipart(String userSub, String fileId, String uploadId) {
    almacen.abortarMultipart(pendiente(userSub, fileId).s3Key(), uploadId);
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

  public String urlDescarga(String userSub, String fileId) {
    ArchivoGuardado g = propio(userSub, fileId);
    if (!"CLEAN".equals(g.status())) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "Solo se puede descargar un archivo limpio (CLEAN)");
    }
    return almacen.urlDescarga(g.s3Key(), disposicion(g.nombre()), VIGENCIA_DESCARGA);
  }

  /** En cualquier estado: primero todas las versiones en ambos buckets y después el registro. */
  public void eliminar(String userSub, String fileId) {
    ArchivoGuardado g = propio(userSub, fileId);
    if ("PENDING".equals(g.status())) {
      // Sin esto las partes de un archivo ya eliminado quedarían hasta 7 días (lifecycle del bucket)
      almacen.abortarSubidasEnCurso(g.s3Key());
    }
    try {
      almacen.borrarTodasLasVersiones(g.s3Key());
    } catch (IllegalStateException e) {
      // La espera de mh-files es corta (timeout de 15 s): solo pasa si se borra justo después de subir
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO,
          "La copia de respaldo sigue en curso; reintenta en unos segundos");
    }
    repositorio.eliminar(userSub, fileId);
  }

  /** RFC 6266: filename ASCII de respaldo (sin tildes; comillas, \ y lo demás a _) y filename* en UTF-8 (RFC 5987). */
  static String disposicion(String nombre) {
    return disposicion("attachment", nombre);
  }

  /** tipo es attachment (descargar) o inline (verlo en el navegador); el nombre va igual en los dos. */
  static String disposicion(String tipo, String nombre) {
    String ascii = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .replaceAll("[^\\x20-\\x7E]|[\"\\\\]", "_");
    StringBuilder utf8 = new StringBuilder();
    for (byte b : nombre.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if (c < 0x80 && (Character.isLetterOrDigit(c) || "!#$&+-.^_`|~".indexOf(c) >= 0)) {
        utf8.append(c);
      } else {
        utf8.append('%').append(String.format("%02X", b & 0xff));
      }
    }
    return tipo + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
  }

  private ArchivoGuardado propio(String userSub, String fileId) {
    return repositorio.obtener(userSub, fileId)
        .orElseThrow(() -> new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "Archivo no encontrado"));
  }

  /** El estado guardado, no el efectivo: un video grande puede tardar más de 1 hora en subir. */
  private ArchivoGuardado pendiente(String userSub, String fileId) {
    ArchivoGuardado g = propio(userSub, fileId);
    if (!"PENDING".equals(g.status())) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "El archivo ya no está pendiente de subir");
    }
    return g;
  }

  private static long partes(ArchivoGuardado g) {
    return (g.size() + TAMANO_PARTE - 1) / TAMANO_PARTE;
  }

  /** Calculado al leer, no se guarda (docs/04). */
  private static String estadoEfectivo(ArchivoGuardado g, long ahora) {
    return "PENDING".equals(g.status()) && ahora - g.createdAt() > VIGENCIA_PENDIENTE ? "UPLOAD_EXPIRED" : g.status();
  }
}
