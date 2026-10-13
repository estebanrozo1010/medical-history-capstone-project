package com.mh.historial.adapter.in.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.application.AlmacenDeArchivos;
import com.mh.historial.application.ArchivoGuardado;
import com.mh.historial.application.ArchivoRepositorio;
import com.mh.historial.application.ArchivoServicio;
import com.mh.historial.application.UrlSubida;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FilesHandlerTest {

  static final String BODY = "{\"carpeta\":\"cardiologia\",\"nombre\":\"examen.pdf\",\"contentType\":\"application/pdf\",\"size\":1000}";
  static final long AHORA = 1_700_000_000L;

  final List<ArchivoGuardado> tabla = new ArrayList<>();
  final Almacen almacen = new Almacen();
  final FilesHandler handler = new FilesHandler(new ArchivoServicio(new Repo(tabla), almacen,
      Clock.fixed(Instant.ofEpochSecond(AHORA), ZoneOffset.UTC)), "http://localhost:4200");

  @Test
  void uploadUrlResponde201YGuardaPendingConElSubDelToken() throws Exception {
    String body = "{\"userSub\":\"sub-b\"," + BODY.substring(1);

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "/files/upload-url", "sub-a", body, null), null);

    assertEquals(201, r.getStatusCode());
    Map<String, Object> json = JSON.std.mapFrom(r.getBody());
    String fileId = (String) json.get("fileId");
    assertEquals("https://s3/sub-a/" + fileId, json.get("url"));
    assertEquals(Map.of("content-type", "application/pdf"), json.get("headers"));
    assertEquals(600, json.get("expiresIn"));
    assertEquals(1, tabla.size());
    assertEquals("sub-a", tabla.get(0).userSub());
    assertEquals("sub-a/" + fileId, tabla.get(0).s3Key());
    assertEquals("PENDING", tabla.get(0).status());
  }

  @Test
  void uploadUrlInvalidoResponde400SinGuardarNada() throws Exception {
    for (String body : new String[] {null, "", "no-json", "[1]",
        BODY.replace("cardiologia", "secreta"),
        BODY.replace("application/pdf", "application/x-msdownload"),
        BODY.replace("1000", "2147483649"),
        BODY.replace("1000", "0"),
        BODY.replace("1000", "\"1000\""),
        BODY.replace("1000", "10.5"),
        BODY.replace("\"examen.pdf\"", "\"\"")}) {
      APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("POST", "/files/upload-url", "sub-a", body, null), null);
      assertEquals(400, r.getStatusCode(), "body: " + body);
      assertEquals("VALIDACION", JSON.std.mapFrom(r.getBody()).get("code"));
    }
    assertTrue(tabla.isEmpty());
  }

  @Test
  void getFilesListaSoloLosDelUsuarioConElContratoDeDocs04() throws Exception {
    handler.handleRequest(peticion("POST", "/files/upload-url", "sub-a", BODY, null), null);
    handler.handleRequest(peticion("POST", "/files/upload-url", "sub-b", BODY, null), null);

    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("GET", "/files", "sub-a", null, null), null);

    assertEquals(200, r.getStatusCode());
    List<Object> lista = JSON.std.listFrom(r.getBody());
    assertEquals(1, lista.size());
    Map<?, ?> a = (Map<?, ?>) lista.get(0);
    assertEquals(tabla.get(0).fileId(), a.get("fileId"));
    assertEquals(Map.of("fileId", a.get("fileId"), "carpeta", "cardiologia", "nombre", "examen.pdf",
        "contentType", "application/pdf", "size", 1000, "status", "PENDING", "createdAt", (int) AHORA), a);
  }

  @Test
  void getFilesConCarpetaFiltraY400SiNoExiste() throws Exception {
    handler.handleRequest(peticion("POST", "/files/upload-url", "sub-a", BODY, null), null);

    assertEquals("[]", handler.handleRequest(peticion("GET", "/files", "sub-a", null, "otros"), null).getBody());
    assertEquals(1, JSON.std.listFrom(
        handler.handleRequest(peticion("GET", "/files", "sub-a", null, "cardiologia"), null).getBody()).size());
    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("GET", "/files", "sub-a", null, "secreta"), null);
    assertEquals(400, r.getStatusCode());
    assertEquals("VALIDACION", JSON.std.mapFrom(r.getBody()).get("code"));
  }

  @Test
  void downloadUrlDeUnCleanResponde200ConUrlYExpiresIn() throws Exception {
    guardado("sub-a", "f1", "CLEAN");

    APIGatewayProxyResponseEvent r = handler.handleRequest(conFileId("GET", "/files/{fileId}/download-url", "sub-a", "f1"), null);

    assertEquals(200, r.getStatusCode());
    assertEquals(Map.of("url", "https://s3/get/sub-a/f1", "expiresIn", 300), JSON.std.mapFrom(r.getBody()));
  }

  @Test
  void downloadUrlDeUnNoCleanResponde409() throws Exception {
    guardado("sub-a", "f1", "PENDING");

    APIGatewayProxyResponseEvent r = handler.handleRequest(conFileId("GET", "/files/{fileId}/download-url", "sub-a", "f1"), null);

    assertEquals(409, r.getStatusCode());
    assertEquals("CONFLICTO", JSON.std.mapFrom(r.getBody()).get("code"));
  }

  @Test
  void fileIdAjenoOInexistenteResponde404EnDescargarYEliminar() throws Exception {
    guardado("sub-a", "f1", "CLEAN");

    for (String[] caso : new String[][] {{"sub-b", "f1"}, {"sub-a", "no-existe"}}) {
      for (APIGatewayProxyRequestEvent p : List.of(conFileId("GET", "/files/{fileId}/download-url", caso[0], caso[1]),
          conFileId("DELETE", "/files/{fileId}", caso[0], caso[1]))) {
        APIGatewayProxyResponseEvent r = handler.handleRequest(p, null);
        assertEquals(404, r.getStatusCode(), p.getHttpMethod() + " " + caso[0]);
        assertEquals("NO_ENCONTRADO", JSON.std.mapFrom(r.getBody()).get("code"));
      }
    }
    assertEquals(1, tabla.size());
    assertTrue(almacen.objetos.containsKey("sub-a/f1"));
  }

  @Test
  void deleteResponde204SinBodyYBorraObjetoYRegistro() {
    guardado("sub-a", "f1", "CLEAN");

    APIGatewayProxyResponseEvent r = handler.handleRequest(conFileId("DELETE", "/files/{fileId}", "sub-a", "f1"), null);

    assertEquals(204, r.getStatusCode());
    assertEquals(null, r.getBody());
    assertEquals("http://localhost:4200", r.getHeaders().get("Access-Control-Allow-Origin"));
    assertTrue(tabla.isEmpty());
    assertFalse(almacen.objetos.containsKey("sub-a/f1"));
  }

  @Test
  void deleteConLaReplicaEnCursoResponde409YConservaElRegistro() throws Exception {
    guardado("sub-a", "f1", "CLEAN");
    FilesHandler replicando = new FilesHandler(new ArchivoServicio(new Repo(tabla), new Almacen() {
      @Override
      public void borrarTodasLasVersiones(String clave) {
        throw new IllegalStateException("La réplica al respaldo sigue pendiente");
      }
    }, Clock.systemUTC()), "http://localhost:4200");

    APIGatewayProxyResponseEvent r = replicando.handleRequest(conFileId("DELETE", "/files/{fileId}", "sub-a", "f1"), null);

    assertEquals(409, r.getStatusCode());
    assertEquals("CONFLICTO", JSON.std.mapFrom(r.getBody()).get("code"));
    assertEquals(1, tabla.size());
  }

  @Test
  void sinClaimsResponde401() {
    APIGatewayProxyRequestEvent sinAuth = new APIGatewayProxyRequestEvent().withHttpMethod("GET").withResource("/files")
        .withRequestContext(new ProxyRequestContext());

    assertEquals(401, handler.handleRequest(sinAuth, null).getStatusCode());
  }

  @Test
  void rutaNoSoportadaResponde405() {
    assertEquals(405, handler.handleRequest(peticion("DELETE", "/files", "sub-a", null, null), null).getStatusCode());
  }

  @Test
  void errorInesperadoResponde500SinDetalles() throws Exception {
    FilesHandler roto = new FilesHandler(new ArchivoServicio(new Repo(tabla) {
      @Override
      public List<ArchivoGuardado> listar(String userSub, String carpeta) {
        throw new IllegalStateException("detalle interno examen.pdf");
      }
    }, new Almacen(), Clock.systemUTC()), "http://localhost:4200");

    APIGatewayProxyResponseEvent r = roto.handleRequest(peticion("GET", "/files", "sub-a", null, null), null);

    assertEquals(500, r.getStatusCode());
    assertEquals("ERROR_INTERNO", JSON.std.mapFrom(r.getBody()).get("code"));
    assertFalse(r.getBody().contains("examen.pdf"));
  }

  @Test
  void todasLasRespuestasLlevanCorsYJson() {
    APIGatewayProxyResponseEvent r = handler.handleRequest(peticion("GET", "/files", "sub-a", null, null), null);

    assertEquals("http://localhost:4200", r.getHeaders().get("Access-Control-Allow-Origin"));
    assertEquals("application/json", r.getHeaders().get("Content-Type"));
  }

  static APIGatewayProxyRequestEvent peticion(String metodo, String ruta, String sub, String body, String carpeta) {
    ProxyRequestContext contexto = new ProxyRequestContext();
    contexto.setAuthorizer(Map.of("claims", Map.of("sub", sub)));
    return new APIGatewayProxyRequestEvent().withHttpMethod(metodo).withResource(ruta).withBody(body)
        .withQueryStringParameters(carpeta == null ? null : Map.of("carpeta", carpeta))
        .withRequestContext(contexto);
  }

  static APIGatewayProxyRequestEvent conFileId(String metodo, String ruta, String sub, String fileId) {
    return peticion(metodo, ruta, sub, null, null).withPathParameters(Map.of("fileId", fileId));
  }

  void guardado(String sub, String fileId, String status) {
    tabla.add(new ArchivoGuardado(sub, fileId, "otros", "a.pdf", "application/pdf", 1, sub + "/" + fileId, status, AHORA));
    almacen.objetos.put(sub + "/" + fileId, new byte[] {1});
  }

  static class Repo implements ArchivoRepositorio {
    final List<ArchivoGuardado> tabla;

    Repo(List<ArchivoGuardado> tabla) {
      this.tabla = tabla;
    }

    @Override
    public void crear(ArchivoGuardado a) {
      tabla.add(a);
    }

    @Override
    public List<ArchivoGuardado> listar(String userSub, String carpeta) {
      return tabla.stream()
          .filter(a -> a.userSub().equals(userSub) && (carpeta == null || a.carpeta().equals(carpeta)))
          .toList();
    }

    @Override
    public Optional<ArchivoGuardado> obtener(String userSub, String fileId) {
      return tabla.stream().filter(a -> a.userSub().equals(userSub) && a.fileId().equals(fileId)).findFirst();
    }

    @Override
    public boolean cambiarEstado(String userSub, String fileId, String de, String a) {
      Optional<ArchivoGuardado> g = obtener(userSub, fileId).filter(x -> x.status().equals(de));
      g.ifPresent(x -> tabla.set(tabla.indexOf(x), new ArchivoGuardado(x.userSub(), x.fileId(), x.carpeta(),
          x.nombre(), x.contentType(), x.size(), x.s3Key(), a, x.createdAt())));
      return g.isPresent();
    }

    @Override
    public void eliminar(String userSub, String fileId) {
      tabla.removeIf(a -> a.userSub().equals(userSub) && a.fileId().equals(fileId));
    }
  }

  static class Almacen implements AlmacenDeArchivos {
    final Map<String, byte[]> objetos = new HashMap<>();

    @Override
    public UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia) {
      return new UrlSubida("https://s3/" + clave, Map.of("content-type", contentType));
    }

    @Override
    public String urlDescarga(String clave, String contentDisposition, Duration vigencia) {
      return "https://s3/get/" + clave;
    }

    @Override
    public byte[] primerosBytes(String clave, int cantidad) {
      byte[] o = objetos.getOrDefault(clave, new byte[0]);
      return Arrays.copyOf(o, Math.min(cantidad, o.length));
    }

    @Override
    public void borrarTodasLasVersiones(String clave) {
      objetos.remove(clave);
    }
  }
}
