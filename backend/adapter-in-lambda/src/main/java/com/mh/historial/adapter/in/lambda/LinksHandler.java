package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoArchivoRepositorio;
import com.mh.historial.adapter.out.dynamodb.DynamoLinkRepositorio;
import com.mh.historial.adapter.out.s3.S3AlmacenDeArchivos;
import com.mh.historial.application.LinkDelUsuario;
import com.mh.historial.application.LinkGuardado;
import com.mh.historial.application.LinkServicio;
import com.mh.historial.domain.ErrorDeDominio;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Lambda mh-links (docs/04): POST/GET /links y PATCH /links/{token} del dueño (el userSub sale del ID token), y las
 * públicas sin authorizer GET /s/{token} y GET /s/{token}/files/{fileId}.
 */
public class LinksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LinkServicio servicio;
  private final String origen;
  private final Map<String, String> cabeceras;

  /** Lambda lo instancia en el init, así el cliente de DynamoDB queda en el snapshot de SnapStart. */
  public LinksHandler() {
    this(DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
        // Solo firma descargas: sin S3Client ni bucket de respaldo (no lista, no borra, no sube)
        new S3AlmacenDeArchivos(S3Presigner.create(), null, System.getenv("DOCS_BUCKET"), null, Duration.ZERO));
  }

  private LinksHandler(DynamoDbClient dynamo, S3AlmacenDeArchivos almacen) {
    this(dynamo, new DynamoArchivoRepositorio(dynamo, System.getenv("FILES_TABLE")), almacen);
  }

  private LinksHandler(DynamoDbClient dynamo, DynamoArchivoRepositorio archivos, S3AlmacenDeArchivos almacen) {
    this(new LinkServicio(new DynamoLinkRepositorio(dynamo, System.getenv("LINKS_TABLE")), archivos, almacen,
        Clock.systemUTC()), System.getenv("ALLOWED_ORIGIN"));
    // Priming de SnapStart (como mh-files): Query al GSI y a mh-files, BatchGetItem, firma en local y GetItem de un
    // token inexistente por las 3 rutas que lo leen, sin escribir nada
    archivos.listar("snapstart-priming", null);
    archivos.obtenerVarios("snapstart-priming", List.of("priming"));
    almacen.urlDescarga("snapstart/priming", "attachment", Duration.ofMinutes(1));
    handleRequest(priming("GET", "/links"), null);
    handleRequest(priming("PATCH", "/links/{token}").withPathParameters(Map.of("token", "A".repeat(43)))
        .withBody("{\"activo\":false}"), null);
    handleRequest(priming("GET", "/s/{token}").withPathParameters(Map.of("token", "A".repeat(43))), null);
    handleRequest(priming("GET", "/s/{token}/files/{fileId}")
        .withPathParameters(Map.of("token", "A".repeat(43), "fileId", "priming")), null);
  }

  private static APIGatewayProxyRequestEvent priming(String metodo, String ruta) {
    APIGatewayProxyRequestEvent.ProxyRequestContext contexto = new APIGatewayProxyRequestEvent.ProxyRequestContext();
    contexto.setAuthorizer(Map.of("claims", Map.of("sub", "snapstart-priming")));
    return new APIGatewayProxyRequestEvent().withHttpMethod(metodo).withResource(ruta).withRequestContext(contexto);
  }

  /** origen es la base del frontend (ALLOWED_ORIGIN): la url compartida es {origen}/compartido/{token}. */
  LinksHandler(LinkServicio servicio, String origen) {
    this.servicio = servicio;
    this.origen = origen;
    // no-store: un 410 se guarda en caché por defecto (y el link se puede volver a encender); el 302 lleva una URL firmada
    this.cabeceras = Map.of("Content-Type", "application/json", "Access-Control-Allow-Origin", origen,
        "Cache-Control", "no-store");
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent evento, Context context) {
    Map<?, ?> claims = evento.getRequestContext() == null || evento.getRequestContext().getAuthorizer() == null
        ? null : (Map<?, ?>) evento.getRequestContext().getAuthorizer().get("claims");
    String ruta = evento.getHttpMethod() + " " + evento.getResource();
    String sub = claims != null && claims.get("sub") instanceof String s ? s : null;
    // Las rutas del dueño ya las protege el authorizer; esto solo evita operar sin dueño si una queda mal configurada.
    // Las /s/* son públicas: solo confían en el token del link
    if (sub == null && !ruta.startsWith("GET /s/")) {
      return error(401, "NO_AUTORIZADO", "Falta el ID token");
    }
    try {
      return switch (ruta) {
        case "POST /links" -> {
          Map<String, Object> m = objeto(evento.getBody());
          // Sin permiso: ambos. Uno que no es texto llega como null y el servicio responde 400
          Object permiso = m.get("permiso");
          LinkGuardado l = servicio.crear(sub, fileIds(m), entero(m, "ttlMinutos"),
              permiso == null ? "ambos" : permiso instanceof String p ? p : null);
          log("LINK_CREADO", l.token(), l.fileIds().size());
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("token", l.token());
          body.put("url", origen + "/compartido/" + l.token());
          body.put("expiresAt", l.expiresAt());
          yield respuesta(201, body);
        }
        case "GET /links" -> respuesta(200, servicio.listar(sub).stream().map(LinksHandler::json).toList());
        case "PATCH /links/{token}" -> {
          if (!(objeto(evento.getBody()).get("activo") instanceof Boolean activo)) {
            throw invalido("activo debe ser true o false");
          }
          LinkGuardado l = servicio.cambiarEstado(sub, evento.getPathParameters().get("token"), activo);
          log(activo ? "LINK_ENCENDIDO" : "LINK_APAGADO", l.token(), l.fileIds().size());
          yield respuesta(200, Map.of("token", l.token(), "activo", l.activo(), "expiresAt", l.expiresAt()));
        }
        case "GET /s/{token}" -> {
          String token = evento.getPathParameters().get("token");
          Optional<LinkDelUsuario> r = servicio.resolver(token);
          if (r.isEmpty()) {
            yield noDisponible(token);
          }
          log("LINK_RESUELTO", token, r.get().archivos().size());
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("expiresAt", r.get().link().expiresAt());
          body.put("permiso", r.get().link().permiso());
          body.put("archivos", r.get().archivos().stream().map(a -> Map.of("fileId", a.fileId(), "nombre", a.nombre(),
              "contentType", a.contentType(), "size", a.size())).toList());
          yield respuesta(200, body);
        }
        case "GET /s/{token}/files/{fileId}" -> {
          String token = evento.getPathParameters().get("token");
          // ?modo=ver lo muestra en el navegador; sin él (o con otro valor) se descarga. El permiso del link manda
          boolean ver = evento.getQueryStringParameters() != null
              && "ver".equals(evento.getQueryStringParameters().get("modo"));
          Optional<String> url = servicio.urlDescarga(token, evento.getPathParameters().get("fileId"), ver);
          if (url.isEmpty()) {
            yield noDisponible(token);
          }
          log("DESCARGA_LINK", token, 1);
          Map<String, String> h = new HashMap<>(cabeceras);
          h.put("Location", url.get());
          yield new APIGatewayProxyResponseEvent().withStatusCode(302).withHeaders(h);
        }
        default -> error(405, "METODO_NO_PERMITIDO", "Método no soportado");
      };
    } catch (ErrorDeDominio e) {
      return switch (e.tipo()) {
        case VALIDACION -> error(400, "VALIDACION", e.getMessage());
        case NO_ENCONTRADO -> error(404, "NO_ENCONTRADO", e.getMessage());
        case CONFLICTO -> error(409, "CONFLICTO", e.getMessage());
      };
    } catch (RuntimeException e) {
      System.out.println("{\"level\":\"ERROR\",\"error\":" + texto(e instanceof SdkException ? e.toString() : e.getClass().getName()) + "}");
      return error(500, "ERROR_INTERNO", "Error interno");
    }
  }

  /** Inexistente, apagado, vencido o mal formado: la misma respuesta, para no revelar cuál fue (docs/02). */
  private APIGatewayProxyResponseEvent noDisponible(String token) {
    log("LINK_NO_DISPONIBLE", token, 0);
    return error(410, "NO_DISPONIBLE", "El enlace no está disponible");
  }

  /** Nunca el token completo: quien lo lea del log podría abrir el link (docs/02). */
  private static void log(String accion, String token, int archivos) {
    String inicio = token == null ? "" : token.substring(0, Math.min(6, token.length()));
    System.out.println("{\"level\":\"INFO\",\"accion\":\"" + accion + "\",\"token\":" + texto(inicio)
        + ",\"archivos\":" + archivos + "}");
  }

  private static Map<String, Object> json(LinkDelUsuario d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("token", d.link().token());
    m.put("archivos", d.archivos().stream().map(a -> Map.of("fileId", a.fileId(), "nombre", a.nombre())).toList());
    m.put("activo", d.link().activo());
    m.put("expiresAt", d.link().expiresAt());
    m.put("permiso", d.link().permiso());
    return m;
  }

  /** La cantidad y los repetidos los valida el servicio. */
  private static List<String> fileIds(Map<String, Object> m) {
    if (!(m.get("fileIds") instanceof List<?> lista) || !lista.stream().allMatch(String.class::isInstance)) {
      throw invalido("fileIds debe ser una lista de fileId");
    }
    return lista.stream().map(String.class::cast).toList();
  }

  private static Map<String, Object> objeto(String body) {
    Map<String, Object> m = null;
    try {
      m = body == null || body.isBlank() ? null : JSON.std.mapFrom(body);
    } catch (IOException e) {
      // body que no es un objeto JSON: se responde 400 abajo
    }
    if (m == null) {
      throw invalido("El body debe ser un objeto JSON");
    }
    return m;
  }

  private static int entero(Map<String, Object> m, String campo) {
    if (!(m.get(campo) instanceof Integer i)) {
      throw invalido(campo + " debe ser un entero");
    }
    return i;
  }

  private static ErrorDeDominio invalido(String mensaje) {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, mensaje);
  }

  private APIGatewayProxyResponseEvent error(int status, String code, String message) {
    return respuesta(status, Map.of("code", code, "message", message));
  }

  private APIGatewayProxyResponseEvent respuesta(int status, Object body) {
    return new APIGatewayProxyResponseEvent().withStatusCode(status).withHeaders(cabeceras).withBody(texto(body));
  }

  private static String texto(Object valor) {
    try {
      return JSON.std.asString(valor);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
