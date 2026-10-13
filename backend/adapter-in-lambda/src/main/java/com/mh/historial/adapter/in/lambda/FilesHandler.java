package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoArchivoRepositorio;
import com.mh.historial.adapter.out.s3.S3AlmacenDeArchivos;
import com.mh.historial.application.ArchivoServicio;
import com.mh.historial.application.Parte;
import com.mh.historial.application.SubidaAutorizada;
import com.mh.historial.application.SubidaMultipart;
import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.SolicitudSubida;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Lambda mh-files: subir (simple y en partes), listar, descargar y eliminar (docs/04). El userSub sale del ID token. */
public class FilesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Timeout de 15 s: si la réplica de un archivo recién subido no termina en 8 s, DELETE responde 409 y se reintenta
  private static final Duration ESPERA_REPLICA = Duration.ofSeconds(8);

  private final ArchivoServicio servicio;
  private final Map<String, String> cabeceras;

  /** Lambda lo instancia en el init, así los clientes de AWS quedan en el snapshot de SnapStart. */
  public FilesHandler() {
    this(new S3AlmacenDeArchivos(S3Presigner.create(),
        S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
        System.getenv("DOCS_BUCKET"), System.getenv("BACKUP_BUCKET"), ESPERA_REPLICA));
  }

  private FilesHandler(S3AlmacenDeArchivos almacen) {
    this(new ArchivoServicio(
            new DynamoArchivoRepositorio(
                DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("FILES_TABLE")),
            almacen,
            Clock.systemUTC()),
        System.getenv("ALLOWED_ORIGIN"));
    // Priming de SnapStart (como mh-profile): firma en local, GET de S3 y de un sub sin archivos, sin escribir nada
    almacen.urlSubida("snapstart/priming", "application/pdf", 1, Duration.ofMinutes(1));
    almacen.urlDescarga("snapstart/priming", "attachment", Duration.ofMinutes(1));
    almacen.urlParte("snapstart/priming", "priming", 1, 1, Duration.ofMinutes(1));
    almacen.primerosBytes("snapstart/priming", 1);
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
        case "GET /files/{fileId}/download-url" -> respuesta(200, Map.of("url", servicio.urlDescarga(sub, fileId(evento)),
            "expiresIn", ArchivoServicio.VIGENCIA_DESCARGA.toSeconds()));
        case "DELETE /files/{fileId}" -> {
          servicio.eliminar(sub, fileId(evento));
          yield sinContenido();
        }
        case "POST /files/multipart/start" -> {
          SubidaMultipart m = servicio.iniciarMultipart(sub, leer(evento.getBody()));
          yield respuesta(201, Map.of("fileId", m.fileId(), "uploadId", m.uploadId(), "partSize", m.partSize()));
        }
        case "POST /files/multipart/part-url" -> {
          Map<String, Object> m = objeto(evento.getBody());
          yield respuesta(200, Map.of("url", servicio.urlParte(sub, obligatorio(m, "fileId"), obligatorio(m, "uploadId"),
              entero(m, "partNumber"))));
        }
        case "POST /files/multipart/complete" -> {
          Map<String, Object> m = objeto(evento.getBody());
          String fileId = obligatorio(m, "fileId");
          servicio.completarMultipart(sub, fileId, obligatorio(m, "uploadId"), partes(m));
          yield respuesta(200, Map.of("fileId", fileId));
        }
        case "POST /files/multipart/abort" -> {
          Map<String, Object> m = objeto(evento.getBody());
          servicio.abortarMultipart(sub, obligatorio(m, "fileId"), obligatorio(m, "uploadId"));
          yield sinContenido();
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
      // Solo el mensaje de errores de AWS: otros podrían traer nombres de archivo
      System.out.println("{\"level\":\"ERROR\",\"error\":" + texto(e instanceof SdkException ? e.toString() : e.getClass().getName()) + "}");
      return error(500, "ERROR_INTERNO", "Error interno");
    }
  }

  private static String carpeta(APIGatewayProxyRequestEvent evento) {
    return evento.getQueryStringParameters() == null ? null : evento.getQueryStringParameters().get("carpeta");
  }

  private static String fileId(APIGatewayProxyRequestEvent evento) {
    return evento.getPathParameters().get("fileId");
  }

  private static SolicitudSubida leer(String body) {
    Map<String, Object> m = objeto(body);
    // Solo enteros JSON; un decimal, texto o número enorme queda en -1 y lo rechaza la validación de tamaño
    long size = m.get("size") instanceof Integer || m.get("size") instanceof Long ? ((Number) m.get("size")).longValue() : -1;
    return new SolicitudSubida(cadena(m, "carpeta"), cadena(m, "nombre"), cadena(m, "contentType"), size);
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

  private static String obligatorio(Map<String, Object> m, String campo) {
    if (!(m.get(campo) instanceof String s) || s.isBlank()) {
      throw invalido(campo + " es obligatorio");
    }
    return s;
  }

  private static int entero(Map<String, Object> m, String campo) {
    if (!(m.get(campo) instanceof Integer i)) {
      throw invalido(campo + " debe ser un entero");
    }
    return i;
  }

  /** La cantidad, el orden y que cada etag no esté vacío los valida el servicio. */
  private static List<Parte> partes(Map<String, Object> m) {
    if (!(m.get("parts") instanceof List<?> lista)) {
      throw invalido("parts es obligatorio");
    }
    List<Parte> partes = new ArrayList<>();
    for (Object o : lista) {
      if (!(o instanceof Map<?, ?> p) || !(p.get("partNumber") instanceof Integer n) || !(p.get("etag") instanceof String e)) {
        throw invalido("cada parte necesita partNumber y etag");
      }
      partes.add(new Parte(n, e));
    }
    return partes;
  }

  private static ErrorDeDominio invalido(String mensaje) {
    return new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, mensaje);
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

  private APIGatewayProxyResponseEvent sinContenido() {
    return new APIGatewayProxyResponseEvent().withStatusCode(204).withHeaders(cabeceras);
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
