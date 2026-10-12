package com.mh.historial.adapter.in.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.application.Cifrador;
import com.mh.historial.application.PerfilGuardado;
import com.mh.historial.application.PerfilRepositorio;
import com.mh.historial.application.PerfilServicio;
import com.mh.historial.domain.ErrorDeDominio;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileHandlerTest {

  static final String BODY = "{\"nombre\":\"Ana\",\"cedula\":\"1020304050\",\"fechaNacimiento\":\"1990-05-17\",\"genero\":\"F\"}";

  final Map<String, PerfilGuardado> tabla = new HashMap<>();
  final ProfileHandler handler = new ProfileHandler(
      new PerfilServicio(new Repo(tabla), new CifradorFalso(), Clock.systemUTC()), "http://localhost:4200");

  @Test
  void postGuardaConElSubDelTokenEIgnoraSubYEmailDelBody() throws Exception {
    String body = "{\"sub\":\"sub-b\",\"email\":\"falso@x.com\"," + BODY.substring(1);

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "sub-a", body), null);

    assertEquals(201, r.getStatusCode());
    assertEquals(Map.of("email", "ana@x.com", "nombre", "Ana", "cedula", "1020304050",
        "fechaNacimiento", "1990-05-17", "genero", "F"), JSON.std.mapFrom(r.getBody()));
    assertTrue(tabla.containsKey("sub-a"));
    assertFalse(tabla.containsKey("sub-b"));
    assertEquals("ana@x.com", tabla.get("sub-a").email());
  }

  @Test
  void segundoPostResponde409() throws Exception {
    handler.handleRequest(peticion("POST", "sub-a", BODY), null);

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "sub-a", BODY), null);

    assertEquals(409, r.getStatusCode());
    assertEquals("CONFLICTO", JSON.std.mapFrom(r.getBody()).get("code"));
  }

  @Test
  void getDevuelveElPerfilDelUsuarioY404ParaOtro() throws Exception {
    handler.handleRequest(peticion("POST", "sub-a", BODY), null);

    APIGatewayProxyResponseEvent propio = handler.handleRequest(peticion("GET", "sub-a", null), null);
    APIGatewayProxyResponseEvent ajeno = handler.handleRequest(peticion("GET", "sub-b", null), null);

    assertEquals(200, propio.getStatusCode());
    assertEquals("1020304050", JSON.std.mapFrom(propio.getBody()).get("cedula"));
    assertEquals(404, ajeno.getStatusCode());
    assertEquals("NO_ENCONTRADO", JSON.std.mapFrom(ajeno.getBody()).get("code"));
  }

  @Test
  void putActualizaY404SiNoExiste() throws Exception {
    assertEquals(404, handler.handleRequest(peticion("PUT", "sub-a", BODY), null).getStatusCode());
    handler.handleRequest(peticion("POST", "sub-a", BODY), null);

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("PUT", "sub-a", BODY.replace("Ana", "Ana María")), null);

    assertEquals(200, r.getStatusCode());
    assertEquals("Ana María", JSON.std.mapFrom(r.getBody()).get("nombre"));
  }

  @Test
  void bodyInvalidoResponde400() throws Exception {
    for (String body : new String[] {null, "", "no-json", "[1]", BODY.replace("\"1020304050\"", "1020304050"),
        BODY.replace("1990-05-17", "17/05/1990")}) {
      APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "sub-a", body), null);
      assertEquals(400, r.getStatusCode(), "body: " + body);
      assertEquals("VALIDACION", JSON.std.mapFrom(r.getBody()).get("code"));
    }
    assertTrue(tabla.isEmpty());
  }

  @Test
  void sinClaimsResponde401() {
    APIGatewayProxyRequestEvent sinAuth = new APIGatewayProxyRequestEvent().withHttpMethod("GET")
        .withRequestContext(new ProxyRequestContext());

    assertEquals(401, handler.handleRequest(sinAuth, null).getStatusCode());
  }

  @Test
  void metodoNoSoportadoResponde405() {
    assertEquals(405, handler.handleRequest(peticion("DELETE", "sub-a", null), null).getStatusCode());
  }

  @Test
  void errorInesperadoResponde500SinDetalles() throws Exception {
    ProfileHandler roto = new ProfileHandler(new PerfilServicio(new Repo(tabla) {
      @Override
      public Optional<PerfilGuardado> buscar(String sub) {
        throw new IllegalStateException("detalle interno 1020304050");
      }
    }, new CifradorFalso(), Clock.systemUTC()), "http://localhost:4200");

    APIGatewayProxyResponseEvent r = roto.handleRequest(peticion("GET", "sub-a", null), null);

    assertEquals(500, r.getStatusCode());
    assertEquals("ERROR_INTERNO", JSON.std.mapFrom(r.getBody()).get("code"));
    assertFalse(r.getBody().contains("1020304050"));
  }

  @Test
  void todasLasRespuestasLlevanCorsYJson() {
    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("GET", "sub-a", null), null);

    assertEquals("http://localhost:4200", r.getHeaders().get("Access-Control-Allow-Origin"));
    assertEquals("application/json", r.getHeaders().get("Content-Type"));
  }

  static APIGatewayProxyRequestEvent peticion(String metodo, String sub, String body) {
    // Así llegan los claims del authorizer de Cognito en la integración proxy
    Map<String, Object> claims = Map.of("sub", sub, "email", sub.replace("sub-a", "ana") + "@x.com");
    ProxyRequestContext contexto = new ProxyRequestContext();
    contexto.setAuthorizer(Map.of("claims", claims));
    return new APIGatewayProxyRequestEvent().withHttpMethod(metodo).withBody(body).withRequestContext(contexto);
  }

  static class CifradorFalso implements Cifrador {
    @Override
    public byte[] cifrar(String texto, String sub) {
      return (sub + "|" + texto).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String descifrar(byte[] cifrado, String sub) {
      return new String(cifrado, StandardCharsets.UTF_8).substring(sub.length() + 1);
    }
  }

  static class Repo implements PerfilRepositorio {
    final Map<String, PerfilGuardado> tabla;

    Repo(Map<String, PerfilGuardado> tabla) {
      this.tabla = tabla;
    }

    @Override
    public Optional<PerfilGuardado> buscar(String sub) {
      return Optional.ofNullable(tabla.get(sub));
    }

    @Override
    public void crear(PerfilGuardado p) {
      if (tabla.putIfAbsent(p.sub(), p) != null) {
        throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "El perfil ya existe");
      }
    }

    @Override
    public void actualizar(PerfilGuardado p) {
      if (tabla.replace(p.sub(), p) == null) {
        throw new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "El perfil no existe");
      }
    }
  }
}
