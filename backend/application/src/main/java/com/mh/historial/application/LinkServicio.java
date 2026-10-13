package com.mh.historial.application;

import com.mh.historial.domain.ErrorDeDominio;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Casos de uso de mh-links (docs/02). Los del dueño toman el userSub del ID token: un link ajeno no se encuentra. Los
 * públicos (resolver, urlDescarga) solo confían en el token del link.
 */
public class LinkServicio {

  static final int MAX_ARCHIVOS = 50;
  static final int MAX_TTL_MINUTOS = 1440;
  // Corta para que, tras apagar el link, recargar la URL de S3 deje de servir pronto. Alcanza: el 302 se sigue al
  // instante y S3 solo valida la firma al empezar, así que una descarga larga no se corta
  static final Duration VIGENCIA_DESCARGA = Duration.ofSeconds(15);
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
  static final Set<String> PERMISOS = Set.of("ver", "descargar", "ambos");

  private final LinkRepositorio links;
  private final ArchivoRepositorio archivos;
  private final AlmacenDeArchivos almacen;
  private final Clock reloj;
  // Se puede crear en el init: AWS valida que SecureRandom sigue siendo único tras el restore de SnapStart
  private final SecureRandom aleatorio = new SecureRandom();

  public LinkServicio(LinkRepositorio links, ArchivoRepositorio archivos, AlmacenDeArchivos almacen, Clock reloj) {
    this.links = links;
    this.archivos = archivos;
    this.almacen = almacen;
    this.reloj = reloj;
  }

  /**
   * Todos los archivos deben ser del usuario (si no, 404) y estar CLEAN (si no, 409). permiso: ver, descargar o ambos.
   */
  public LinkGuardado crear(String userSub, List<String> fileIds, int ttlMinutos, String permiso) {
    if (fileIds == null || fileIds.isEmpty() || fileIds.size() > MAX_ARCHIVOS) {
      throw validacion("fileIds debe traer de 1 a " + MAX_ARCHIVOS + " archivos");
    }
    if (new HashSet<>(fileIds).size() != fileIds.size()) {
      throw validacion("fileIds no puede traer archivos repetidos");
    }
    if (ttlMinutos < 1 || ttlMinutos > MAX_TTL_MINUTOS) {
      throw validacion("ttlMinutos debe estar entre 1 y " + MAX_TTL_MINUTOS);
    }
    if (permiso == null || !PERMISOS.contains(permiso)) {
      throw validacion("permiso debe ser ver, descargar o ambos");
    }
    Map<String, ArchivoGuardado> propios = propios(userSub);
    if (!propios.keySet().containsAll(fileIds)) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "Archivo no encontrado");
    }
    if (fileIds.stream().anyMatch(id -> !"CLEAN".equals(propios.get(id).status()))) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "Solo se pueden compartir archivos limpios (CLEAN)");
    }
    byte[] bytes = new byte[32];
    aleatorio.nextBytes(bytes);
    long ahora = reloj.instant().getEpochSecond();
    LinkGuardado link = new LinkGuardado(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), userSub,
        List.copyOf(fileIds), true, ahora + ttlMinutos * 60L, ahora, permiso);
    links.crear(link);
    return link;
  }

  /** Solo los vigentes, del más nuevo al más viejo; un archivo eliminado ya no aparece en su link. */
  public List<LinkDelUsuario> listar(String userSub) {
    long ahora = reloj.instant().getEpochSecond();
    List<LinkGuardado> vigentes = links.listar(userSub).stream().filter(l -> ahora < l.expiresAt())
        .sorted(Comparator.comparingLong(LinkGuardado::createdAt).reversed()).toList();
    if (vigentes.isEmpty()) {
      return List.of();
    }
    Map<String, ArchivoGuardado> propios = propios(userSub);
    return vigentes.stream().map(l -> new LinkDelUsuario(l,
        l.fileIds().stream().filter(propios::containsKey).map(propios::get).toList())).toList();
  }

  /** El switch manual. Ajeno, inexistente o vencido: el mismo 404. */
  public LinkGuardado cambiarEstado(String userSub, String token, boolean activo) {
    long ahora = reloj.instant().getEpochSecond();
    // Un token mal formado ni se busca: DynamoDB rechaza claves vacías o enormes con un 500
    LinkGuardado l = (token != null && TOKEN.matcher(token).matches() ? links.obtener(token)
        : Optional.<LinkGuardado>empty())
        .filter(x -> x.userSub().equals(userSub) && ahora < x.expiresAt())
        .orElseThrow(LinkServicio::noEncontrado);
    if (!links.cambiarActivo(token, activo)) {
      throw noEncontrado();
    }
    return new LinkGuardado(l.token(), l.userSub(), l.fileIds(), activo, l.expiresAt(), l.createdAt(), l.permiso());
  }

  /**
   * Pública (GET /s/{token}): vacío si no existe, está apagado o venció (el handler responde el mismo 410). Si está
   * vigente, solo sus archivos CLEAN que aún existen, en el orden de
   * fileIds (un SS de DynamoDB: no es el orden en que se eligieron, pero sí estable entre lecturas).
   */
  public Optional<LinkDelUsuario> resolver(String token) {
    return vigente(token).map(l -> {
      Map<String, ArchivoGuardado> limpios = archivos.obtenerVarios(l.userSub(), l.fileIds()).stream()
          .filter(a -> "CLEAN".equals(a.status()))
          .collect(Collectors.toMap(ArchivoGuardado::fileId, Function.identity()));
      return new LinkDelUsuario(l, l.fileIds().stream().filter(limpios::containsKey).map(limpios::get).toList());
    });
  }

  /**
   * Pública (GET /s/{token}/files/{fileId}): vacío como en resolver; NO_ENCONTRADO si el archivo no está en el link,
   * ya no existe o no está CLEAN. ver=true firma inline (el navegador lo muestra); si no, attachment (descarga), salvo
   * que el permiso del link fije uno. La clave es {userSub del link}/{fileId}: no se puede pedir un archivo de otro.
   */
  public Optional<String> urlDescarga(String token, String fileId, boolean ver) {
    return vigente(token).map(l -> {
      ArchivoGuardado a = (l.fileIds().contains(fileId) ? archivos.obtener(l.userSub(), fileId)
          : Optional.<ArchivoGuardado>empty())
          .filter(x -> "CLEAN".equals(x.status()))
          .orElseThrow(() -> new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "Archivo no disponible"));
      // El permiso manda sobre ?modo: cambiar la URL no habilita lo que quien compartió no permitió.
      // Solo ver no impide guardar: el navegador igual puede guardar lo que muestra
      boolean inline = switch (l.permiso()) {
        case "ver" -> true;
        case "descargar" -> false;
        default -> ver;
      };
      return almacen.urlDescarga(a.s3Key(), ArchivoServicio.disposicion(inline ? "inline" : "attachment", a.nombre()),
          VIGENCIA_DESCARGA);
    });
  }

  private Optional<LinkGuardado> vigente(String token) {
    long ahora = reloj.instant().getEpochSecond();
    // Mal formado ni se busca; expiresAt en código porque el TTL de DynamoDB borra con hasta ~48 h de retraso
    return (token != null && TOKEN.matcher(token).matches() ? links.obtener(token) : Optional.<LinkGuardado>empty())
        .filter(l -> l.activo() && ahora < l.expiresAt());
  }

  // shortcut: un Query consistente con todos los archivos del usuario; si alguien llega a miles, BatchGetItem de los fileIds
  private Map<String, ArchivoGuardado> propios(String userSub) {
    return archivos.listar(userSub, null).stream()
        .collect(Collectors.toMap(ArchivoGuardado::fileId, Function.identity()));
  }

  private static ErrorDeDominio validacion(String mensaje) {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, mensaje);
  }

  private static ErrorDeDominio noEncontrado() {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "Link no encontrado");
  }
}
