package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoArchivoRepositorio;
import com.mh.historial.adapter.out.s3.S3AlmacenDeArchivos;
import com.mh.historial.application.ArchivoServicio;
import com.mh.historial.application.SubidaAutorizada;
import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.SolicitudSubida;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Lambda mh-files: POST /files/upload-url y GET /files (docs/04). El userSub sale del ID token. */
public class FilesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final ArchivoServicio servicio;
  private final Map<String, String> cabeceras;

  /** Lambda lo instancia en el init, así los clientes de AWS quedan en el snapshot de SnapStart. */
  public FilesHandler() {
    this(new S3AlmacenDeArchivos(S3Presigner.create(), System.getenv("DOCS_BUCKET")));
  }

  private FilesHandler(S3AlmacenDeArchivos almacen) {
    this(new ArchivoServicio(
            new DynamoArchivoRepositorio(
                DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("FILES_TABLE")),
            almacen,
            Clock.systemUTC()),
        System.getenv("ALLOWED_ORIGIN"));
    // Priming de SnapStart (como mh-profile): firma en local y GET de un sub sin archivos, sin escribir nada
    almacen.urlSubida("snapstart/priming", "application/pdf", 1, Duration.ofMinutes(1));
    APIGatewayProxyRequestEvent.ProxyRequestContext contexto = new APIGatewayProxyRequestEvent.ProxyRequestContext();
    contexto.setAuthorizer(Map.of("claims", Map.of("sub", "snapstart-priming")));
    handleRequest(new APIGatewayProxyRequestEvent().withHttpMethod("GET").withResource("/files")
        .withRequestContext(contexto), null);
  }

  FilesHandler(ArchivoServicio servicio, String origenPermitido) {
    this.servicio = servicio;
    this.cabeceras = Map.of("Content-Type", "application/json", "Access-Control-Allow-Origin", origenPermitido);
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
        case "POST /files/upload-url" -> subida(servicio.solicitarSubida(sub, leer(evento.getBody())));
        case "GET /files" -> respuesta(200, servicio.listar(sub, carpeta(evento)).stream().map(FilesHandler::json).toList());
        default -> error(405, "METODO_NO_PERMITIDO", "Método no soportado");
      };
    } catch (ErrorDeDominio e) {
      return switch (e.tipo()) {
        case VALIDACION -> error(400, "VALIDACION", e.getMessage());
        case NO_ENCONTRADO -> error(404, "NO_ENCONTRADO", e.getMessage());
        case CONFLICTO -> error(409, "CONFLICTO", e.getMessage());
      };
    } catch (RuntimeException e) {
      // Solo el mensaje de errores de AWS: otros podrían traer nombres de archivo
      System.out.println("{\"level\":\"ERROR\",\"error\":" + texto(e instanceof SdkException ? e.toString() : e.getClass().getName()) + "}");
      return error(500, "ERROR_INTERNO", "Error interno");
    }
  }

  private static String carpeta(APIGatewayProxyRequestEvent evento) {
    return evento.getQueryStringParameters() == null ? null : evento.getQueryStringParameters().get("carpeta");
  }

  private static SolicitudSubida leer(String body) {
    Map<String, Object> m = null;
    try {
      m = body == null || body.isBlank() ? null : JSON.std.mapFrom(body);
    } catch (IOException e) {
      // body que no es un objeto JSON: se responde 400 abajo
    }
    if (m == null) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, "El body debe ser un objeto JSON");
    }
    // Solo enteros JSON; un decimal, texto o número enorme queda en -1 y lo rechaza la validación de tamaño
    long size = m.get("size") instanceof Integer || m.get("size") instanceof Long ? ((Number) m.get("size")).longValue() : -1;
    return new SolicitudSubida(cadena(m, "carpeta"), cadena(m, "nombre"), cadena(m, "contentType"), size);
  }

  private static String cadena(Map<String, Object> m, String campo) {
    return m.get(campo) instanceof String s ? s : null;
  }

  private static Map<String, Object> json(Archivo a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("fileId", a.fileId());
    m.put("carpeta", a.carpeta());
    m.put("nombre", a.nombre());
    m.put("contentType", a.contentType());
    m.put("size", a.size());
    m.put("status", a.status());
    m.put("createdAt", a.createdAt());
    return m;
  }

  private APIGatewayProxyResponseEvent subida(SubidaAutorizada s) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fileId", s.fileId());
    body.put("url", s.url());
    body.put("headers", s.headers());
    body.put("expiresIn", s.expiresIn());
    return respuesta(201, body);
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
