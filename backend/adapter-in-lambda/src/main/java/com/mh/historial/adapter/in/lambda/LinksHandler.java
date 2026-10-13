package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoArchivoRepositorio;
import com.mh.historial.adapter.out.dynamodb.DynamoLinkRepositorio;
import com.mh.historial.application.LinkDelUsuario;
import com.mh.historial.application.LinkGuardado;
import com.mh.historial.application.LinkServicio;
import com.mh.historial.domain.ErrorDeDominio;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Lambda mh-links: POST/GET /links y PATCH /links/{token} del dueño (docs/04). El userSub sale del ID token. */
public class LinksHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LinkServicio servicio;
  private final String origen;
  private final Map<String, String> cabeceras;

  /** Lambda lo instancia en el init, así el cliente de DynamoDB queda en el snapshot de SnapStart. */
  public LinksHandler() {
    this(DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build());
  }

  private LinksHandler(DynamoDbClient dynamo) {
    this(dynamo, new DynamoArchivoRepositorio(dynamo, System.getenv("FILES_TABLE")));
  }

  private LinksHandler(DynamoDbClient dynamo, DynamoArchivoRepositorio archivos) {
    this(new LinkServicio(new DynamoLinkRepositorio(dynamo, System.getenv("LINKS_TABLE")), archivos, Clock.systemUTC()),
        System.getenv("ALLOWED_ORIGIN"));
    // Priming de SnapStart (como mh-files): Query al GSI y a mh-files, y GetItem de un token inexistente, sin escribir nada
    archivos.listar("snapstart-priming", null);
    handleRequest(priming("GET", "/links"), null);
    handleRequest(priming("PATCH", "/links/{token}").withPathParameters(Map.of("token", "A".repeat(43)))
        .withBody("{\"activo\":false}"), null);
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
    this.cabeceras = Map.of("Content-Type", "application/json", "Access-Control-Allow-Origin", origen);
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent evento, Context context) {
    Map<?, ?> claims = evento.getRequestContext() == null || evento.getRequestContext().getAuthorizer() == null
        ? null : (Map<?, ?>) evento.getRequestContext().getAuthorizer().get("claims");
    // El authorizer ya lo exige; esto solo evita operar sin dueño si la ruta queda mal configurada
    if (claims == null || !(claims.get("sub") instanceof String sub)) {
      return error(401, "NO_AUTORIZADO", "Falta el ID token");
    }
    try {
      return switch (evento.getHttpMethod() + " " + evento.getResource()) {
        case "POST /links" -> {
          Map<String, Object> m = objeto(evento.getBody());
          LinkGuardado l = servicio.crear(sub, fileIds(m), entero(m, "ttlMinutos"));
          log("LINK_CREADO", l);
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
          log(activo ? "LINK_ENCENDIDO" : "LINK_APAGADO", l);
          yield respuesta(200, Map.of("token", l.token(), "activo", l.activo(), "expiresAt", l.expiresAt()));
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

  /** Nunca el token completo: quien lo lea del log podría abrir el link (docs/02). */
  private static void log(String accion, LinkGuardado l) {
    System.out.println("{\"level\":\"INFO\",\"accion\":\"" + accion + "\",\"token\":" + texto(l.token().substring(0, 6))
        + ",\"archivos\":" + l.fileIds().size() + "}");
  }

  private static Map<String, Object> json(LinkDelUsuario d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("token", d.link().token());
    m.put("archivos", d.archivos().stream().map(a -> Map.of("fileId", a.fileId(), "nombre", a.nombre())).toList());
    m.put("activo", d.link().activo());
    m.put("expiresAt", d.link().expiresAt());
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
