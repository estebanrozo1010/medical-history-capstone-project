package com.mh.historial.application;

import com.mh.historial.domain.ErrorDeDominio;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Casos de uso de mh-links para el dueño (docs/02). El userSub sale del ID token: un link ajeno no se encuentra. */
public class LinkServicio {

  static final int MAX_ARCHIVOS = 50;
  static final int MAX_TTL_MINUTOS = 1440;
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final LinkRepositorio links;
  private final ArchivoRepositorio archivos;
  private final Clock reloj;
  // Se puede crear en el init: AWS valida que SecureRandom sigue siendo único tras el restore de SnapStart
  private final SecureRandom aleatorio = new SecureRandom();

  public LinkServicio(LinkRepositorio links, ArchivoRepositorio archivos, Clock reloj) {
    this.links = links;
    this.archivos = archivos;
    this.reloj = reloj;
  }

  /** Todos los archivos deben ser del usuario (si no, 404) y estar CLEAN (si no, 409). */
  public LinkGuardado crear(String userSub, List<String> fileIds, int ttlMinutos) {
    if (fileIds == null || fileIds.isEmpty() || fileIds.size() > MAX_ARCHIVOS) {
      throw validacion("fileIds debe traer de 1 a " + MAX_ARCHIVOS + " archivos");
    }
    if (new HashSet<>(fileIds).size() != fileIds.size()) {
      throw validacion("fileIds no puede traer archivos repetidos");
    }
    if (ttlMinutos < 1 || ttlMinutos > MAX_TTL_MINUTOS) {
      throw validacion("ttlMinutos debe estar entre 1 y " + MAX_TTL_MINUTOS);
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
        List.copyOf(fileIds), true, ahora + ttlMinutos * 60L, ahora);
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
    return new LinkGuardado(l.token(), l.userSub(), l.fileIds(), activo, l.expiresAt(), l.createdAt());
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
