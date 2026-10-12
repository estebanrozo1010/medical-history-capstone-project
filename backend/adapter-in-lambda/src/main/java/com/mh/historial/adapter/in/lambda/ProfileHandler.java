package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoPerfilRepositorio;
import com.mh.historial.adapter.out.kms.KmsCifrador;
import com.mh.historial.application.PerfilServicio;
import com.mh.historial.domain.DatosPerfil;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.Perfil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

/** Lambda mh-profile: POST/GET/PUT /profile (docs/04). El sub y el email salen del ID token. */
public class ProfileHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final PerfilServicio servicio;
  private final Map<String, String> cabeceras;

  /** Lambda lo instancia en el init, así los clientes de AWS quedan en el snapshot de SnapStart. */
  public ProfileHandler() {
    this(new KmsCifrador(KmsClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
        System.getenv("KMS_KEY_ARN")));
  }

  private ProfileHandler(KmsCifrador cifrador) {
    this(new PerfilServicio(
            new DynamoPerfilRepositorio(
                DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                System.getenv("PROFILES_TABLE")),
            cifrador,
            Clock.systemUTC()),
        System.getenv("ALLOWED_ORIGIN"));
    // Priming de SnapStart: sin esto la primera petición tras el restore tarda ~2.8 s cargando el SDK
    cifrador.cifrar("priming", "snapstart");
    handleRequest(get("snapstart-priming"), null);
  }

  /** GET de un sub que no existe: recorre handler, DynamoDB y JSON sin escribir nada. */
  private static APIGatewayProxyRequestEvent get(String sub) {
    APIGatewayProxyRequestEvent.ProxyRequestContext contexto = new APIGatewayProxyRequestEvent.ProxyRequestContext();
    contexto.setAuthorizer(Map.of("claims", Map.of("sub", sub)));
    return new APIGatewayProxyRequestEvent().withHttpMethod("GET").withRequestContext(contexto);
  }

  ProfileHandler(PerfilServicio servicio, String origenPermitido) {
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
    String email = (String) claims.get("email");
    try {
      return switch (evento.getHttpMethod()) {
        case "POST" -> perfil(201, servicio.registrar(sub, email, leer(evento.getBody())));
        case "GET" -> perfil(200, servicio.obtener(sub));
        case "PUT" -> perfil(200, servicio.actualizar(sub, email, leer(evento.getBody())));
        default -> error(405, "METODO_NO_PERMITIDO", "Método no soportado");
      };
    } catch (ErrorDeDominio e) {
      return switch (e.tipo()) {
        case VALIDACION -> error(400, "VALIDACION", e.getMessage());
        case NO_ENCONTRADO -> error(404, "NO_ENCONTRADO", e.getMessage());
        case CONFLICTO -> error(409, "CONFLICTO", e.getMessage());
      };
    } catch (RuntimeException e) {
      // Solo el mensaje de errores de AWS: otros podrían traer datos del perfil
      System.out.println("{\"level\":\"ERROR\",\"error\":" + texto(e instanceof SdkException ? e.toString() : e.getClass().getName()) + "}");
      return error(500, "ERROR_INTERNO", "Error interno");
    }
  }

  private static DatosPerfil leer(String body) {
    Map<String, Object> m = null;
    try {
      m = body == null || body.isBlank() ? null : JSON.std.mapFrom(body);
    } catch (IOException e) {
      // body que no es un objeto JSON: se responde 400 abajo
    }
    if (m == null) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.VALIDACION, "El body debe ser un objeto JSON");
    }
    return DatosPerfil.de(cadena(m, "nombre"), cadena(m, "cedula"), cadena(m, "fechaNacimiento"), cadena(m, "genero"));
  }

  private static String cadena(Map<String, Object> m, String campo) {
    return m.get(campo) instanceof String s ? s : null;
  }

  private APIGatewayProxyResponseEvent perfil(int status, Perfil p) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", p.email());
    body.put("nombre", p.datos().nombre());
    body.put("cedula", p.datos().cedula());
    body.put("fechaNacimiento", p.datos().fechaNacimiento().toString());
    body.put("genero", p.datos().genero());
    return respuesta(status, body);
  }

  private APIGatewayProxyResponseEvent error(int status, String code, String message) {
    return respuesta(status, Map.of("code", code, "message", message));
  }

  private APIGatewayProxyResponseEvent respuesta(int status, Map<String, Object> body) {
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
