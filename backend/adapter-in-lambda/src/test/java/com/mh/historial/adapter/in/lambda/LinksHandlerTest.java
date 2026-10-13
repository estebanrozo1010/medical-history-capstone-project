package com.mh.historial.adapter.in.lambda;

import static com.mh.historial.adapter.in.lambda.FilesHandlerTest.peticion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.application.ArchivoGuardado;
import com.mh.historial.application.LinkGuardado;
import com.mh.historial.application.LinkRepositorio;
import com.mh.historial.application.LinkServicio;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinksHandlerTest {

  static final long AHORA = 1_700_000_000L;

  final List<ArchivoGuardado> archivos = new ArrayList<>();
  final Links links = new Links();
  final LinksHandler handler = new LinksHandler(new LinkServicio(links, new FilesHandlerTest.Repo(archivos),
      Clock.fixed(Instant.ofEpochSecond(AHORA), ZoneOffset.UTC)), "http://localhost:4200");

  @Test
  void postCreaElLinkYRespondeTokenUrlDelFrontYExpiresAt() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "f2", "CLEAN");

    APIGatewayProxyResponseEvent r = handler.handleRequest(
        peticion("POST", "/links", "sub-a", "{\"fileIds\":[\"f1\",\"f2\"],\"ttlMinutos\":30,\"userSub\":\"sub-b\"}", null),
        null);

    assertEquals(201, r.getStatusCode());
    Map<String, Object> json = JSON.std.mapFrom(r.getBody());
    String token = (String) json.get("token");
    assertEquals(Map.of("token", token, "url", "http://localhost:4200/compartido/" + token,
        "expiresAt", (int) (AHORA + 1800)), json);
    assertEquals("sub-a", links.datos.get(token).userSub());
  }

  @Test
  void postInvalidoResponde400() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    for (String body : new String[] {null, "", "no-json", "[1]", "{\"ttlMinutos\":10}",
        "{\"fileIds\":\"f1\",\"ttlMinutos\":10}", "{\"fileIds\":[1],\"ttlMinutos\":10}",
        "{\"fileIds\":[\"f1\"]}", "{\"fileIds\":[\"f1\"],\"ttlMinutos\":\"10\"}",
        "{\"fileIds\":[\"f1\"],\"ttlMinutos\":10.5}", "{\"fileIds\":[\"f1\"],\"ttlMinutos\":0}",
        "{\"fileIds\":[\"f1\",\"f1\"],\"ttlMinutos\":10}", "{\"fileIds\":[],\"ttlMinutos\":10}"}) {
      APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "/links", "sub-a", body, null), null);
      assertEquals(400, r.getStatusCode(), "body: " + body);
      assertEquals("VALIDACION", JSON.std.mapFrom(r.getBody()).get("code"));
    }
    assertTrue(links.datos.isEmpty());
  }

  @Test
  void postConArchivoAjenoResponde404YConUnoNoCleanResponde409() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "pend", "PENDING");
    archivo("sub-b", "fb", "CLEAN");

    APIGatewayProxyResponseEvent ajeno = handler.handleRequest(
        peticion("POST", "/links", "sub-a", "{\"fileIds\":[\"f1\",\"fb\"],\"ttlMinutos\":10}", null), null);
    APIGatewayProxyResponseEvent sucio = handler.handleRequest(
        peticion("POST", "/links", "sub-a", "{\"fileIds\":[\"f1\",\"pend\"],\"ttlMinutos\":10}", null), null);

    assertEquals(404, ajeno.getStatusCode());
    assertEquals("NO_ENCONTRADO", JSON.std.mapFrom(ajeno.getBody()).get("code"));
    assertEquals(409, sucio.getStatusCode());
    assertEquals("CONFLICTO", JSON.std.mapFrom(sucio.getBody()).get("code"));
  }

  @Test
  void getListaLosLinksDelUsuarioConElContratoDeDocs04() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-b", "fb", "CLEAN");
    String token = crear("sub-a", "f1");
    crear("sub-b", "fb");

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("GET", "/links", "sub-a", null, null), null);

    assertEquals(200, r.getStatusCode());
    assertEquals(List.of(Map.of("token", token, "archivos", List.of(Map.of("fileId", "f1", "nombre", "f1.pdf")),
        "activo", true, "expiresAt", (int) (AHORA + 600))), JSON.std.listFrom(r.getBody()));
  }

  @Test
  void patchDelDuenoApagaElLinkY200() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    String token = crear("sub-a", "f1");

    APIGatewayProxyResponseEvent r = handler.handleRequest(patch("sub-a", token, "{\"activo\":false}"), null);

    assertEquals(200, r.getStatusCode());
    assertEquals(Map.of("token", token, "activo", false, "expiresAt", (int) (AHORA + 600)), JSON.std.mapFrom(r.getBody()));
    assertFalse(links.datos.get(token).activo());
  }

  @Test
  void patchAjenoOInexistenteResponde404YBodyInvalido400() throws Exception {
    archivo("sub-a", "f1", "CLEAN");
    String token = crear("sub-a", "f1");

    for (APIGatewayProxyRequestEvent p : List.of(patch("sub-b", token, "{\"activo\":false}"),
        patch("sub-a", "A".repeat(43), "{\"activo\":false}"))) {
      APIGatewayProxyResponseEvent r = handler.handleRequest(p, null);
      assertEquals(404, r.getStatusCode());
      assertEquals("NO_ENCONTRADO", JSON.std.mapFrom(r.getBody()).get("code"));
    }
    for (String body : new String[] {null, "{}", "{\"activo\":\"false\"}", "{\"activo\":0}"}) {
      assertEquals(400, handler.handleRequest(patch("sub-a", token, body), null).getStatusCode(), "body: " + body);
    }
    assertTrue(links.datos.get(token).activo());
  }

  @Test
  void elLogSoloTraeLosPrimeros6CaracteresDelToken() {
    archivo("sub-a", "f1", "CLEAN");
    PrintStream original = System.out;
    ByteArrayOutputStream salida = new ByteArrayOutputStream();
    String token;
    try {
      System.setOut(new PrintStream(salida, true, StandardCharsets.UTF_8));
      token = crear("sub-a", "f1");
      handler.handleRequest(patch("sub-a", token, "{\"activo\":false}"), null);
    } finally {
      System.setOut(original);
    }

    String log = salida.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains(token.substring(0, 6)), log);
    assertFalse(log.contains(token.substring(0, 7)), log);
  }

  @Test
  void sinClaims401YMetodoDesconocido405ConCors() {
    APIGatewayProxyResponseEvent sinToken = handler.handleRequest(
        new APIGatewayProxyRequestEvent().withHttpMethod("GET").withResource("/links"), null);
    APIGatewayProxyResponseEvent otro = handler.handleRequest(peticion("DELETE", "/links", "sub-a", null, null), null);

    assertEquals(401, sinToken.getStatusCode());
    assertEquals(405, otro.getStatusCode());
    assertEquals("http://localhost:4200", otro.getHeaders().get("Access-Control-Allow-Origin"));
  }

  String crear(String sub, String fileId) {
    try {
      return (String) JSON.std.mapFrom(handler.handleRequest(peticion("POST", "/links", sub,
          "{\"fileIds\":[\"" + fileId + "\"],\"ttlMinutos\":10}", null), null).getBody()).get("token");
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  static APIGatewayProxyRequestEvent patch(String sub, String token, String body) {
    return peticion("PATCH", "/links/{token}", sub, body, null).withPathParameters(Map.of("token", token));
  }

  void archivo(String sub, String fileId, String status) {
    archivos.add(new ArchivoGuardado(sub, fileId, "otros", fileId + ".pdf", "application/pdf", 1, sub + "/" + fileId,
        status, AHORA));
  }

  static class Links implements LinkRepositorio {
    final Map<String, LinkGuardado> datos = new LinkedHashMap<>();

    @Override
    public void crear(LinkGuardado l) {
      datos.put(l.token(), l);
    }

    @Override
    public List<LinkGuardado> listar(String userSub) {
      return datos.values().stream().filter(l -> l.userSub().equals(userSub)).toList();
    }

    @Override
    public Optional<LinkGuardado> obtener(String token) {
      return Optional.ofNullable(datos.get(token));
    }

    @Override
    public boolean cambiarActivo(String token, boolean activo) {
      LinkGuardado l = datos.get(token);
      datos.put(token, new LinkGuardado(l.token(), l.userSub(), l.fileIds(), activo, l.expiresAt(), l.createdAt()));
      return true;
    }
  }
}
