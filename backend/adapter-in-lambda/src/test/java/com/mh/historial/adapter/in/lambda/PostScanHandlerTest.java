package com.mh.historial.adapter.in.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.in.lambda.FilesHandlerTest.Almacen;
import com.mh.historial.adapter.in.lambda.FilesHandlerTest.Repo;
import com.mh.historial.application.ArchivoGuardado;
import com.mh.historial.application.PostScanServicio;
import com.mh.historial.application.ValidadorDeTipo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostScanHandlerTest {

  static final String SUB = "0f1e2d3c-aaaa-bbbb-cccc-000000000001";
  static final String PDF_ID = "94531068-906b-4dd4-a029-25dc610955cc";
  static final String EXE_ID = "0b6c7a52-1d1e-4c55-9d7e-3b1c2f0e9a11";

  final List<ArchivoGuardado> tabla = new ArrayList<>();
  final Almacen almacen = new Almacen();
  final Repo repo = new Repo(tabla);
  final PostScanHandler handler = new PostScanHandler(new PostScanServicio(repo, new ValidadorDeTipo(almacen), almacen));

  @Test
  void pdfRealPasaACleanYExeRenombradoARejectedBorrado() throws Exception {
    subido(PDF_ID, "%PDF-1.7\n");
    subido(EXE_ID, "MZ\u0090\u0000\u0003");

    assertEquals("CLEAN", handler.handleRequest(evento(SUB + "/" + PDF_ID), null));
    assertEquals("REJECTED", handler.handleRequest(evento(SUB + "/" + EXE_ID), null));

    assertEquals(List.of("CLEAN", "REJECTED"), tabla.stream().map(ArchivoGuardado::status).toList());
    assertTrue(almacen.objetos.containsKey(SUB + "/" + PDF_ID));
    assertFalse(almacen.objetos.containsKey(SUB + "/" + EXE_ID));
  }

  @Test
  void reenviarElMismoEventoNoFalla() throws Exception {
    subido(EXE_ID, "MZ");
    handler.handleRequest(evento(SUB + "/" + EXE_ID), null);

    assertEquals("SIN_CAMBIOS", handler.handleRequest(evento(SUB + "/" + EXE_ID), null));
    assertEquals("REJECTED", tabla.get(0).status());
  }

  @Test
  void objetoSinRegistroSeBorraYConClaveAjenaSeIgnora() throws Exception {
    almacen.objetos.put(SUB + "/" + PDF_ID, "%PDF".getBytes());

    assertEquals("HUERFANO_BORRADO", handler.handleRequest(evento(SUB + "/" + PDF_ID), null));
    assertFalse(almacen.objetos.containsKey(SUB + "/" + PDF_ID));
    assertEquals("IGNORADO", handler.handleRequest(evento("otra/cosa.txt"), null));
  }

  @Test
  void eventoSinDetalleDeObjetoSeIgnora() throws Exception {
    assertEquals("IGNORADO", handler.handleRequest(JSON.std.mapFrom("{\"detail-type\":\"Object Created\"}"), null));
    assertEquals("IGNORADO", handler.handleRequest(JSON.std.mapFrom("{\"detail\":{\"object\":\"x\"}}"), null));
  }

  @Test
  void unErrorDeAwsSePropagaParaQueLaLambdaReintente() throws Exception {
    PostScanHandler roto = new PostScanHandler(new PostScanServicio(new Repo(tabla) {
      @Override
      public java.util.Optional<ArchivoGuardado> obtener(String userSub, String fileId) {
        throw new IllegalStateException("DynamoDB no disponible");
      }
    }, new ValidadorDeTipo(almacen), almacen));

    assertThrows(IllegalStateException.class, () -> roto.handleRequest(evento(SUB + "/" + PDF_ID), null));
  }

  private void subido(String fileId, String contenido) {
    String clave = SUB + "/" + fileId;
    tabla.add(new ArchivoGuardado(SUB, fileId, "laboratorio", "examen.pdf", "application/pdf", 9, clave, "PENDING", 0));
    almacen.objetos.put(clave, contenido.getBytes(StandardCharsets.ISO_8859_1));
  }

  /** Evento "Object Created" de S3 en EventBridge, como lo entrega Lambda (JSON a Map). */
  static Map<String, Object> evento(String clave) throws Exception {
    return JSON.std.mapFrom("""
        {"version":"0","id":"17793124-05d4-b198-2fde-7ededc63b103","detail-type":"Object Created",
         "source":"aws.s3","account":"123456789012","time":"2026-10-12T10:00:00Z","region":"us-east-1",
         "resources":["arn:aws:s3:::mh-docs-123456789012"],
         "detail":{"version":"0","bucket":{"name":"mh-docs-123456789012"},
          "object":{"key":"%s","size":9,"etag":"b1946ac92492d2347c6235b4d2611184","version-id":"IYV3p45BT0ac8hjHg1houSdS1a.Mro8e","sequencer":"00617F08299329D189"},
          "request-id":"N4N7GDK58NMKJ12R","requester":"123456789012","source-ip-address":"1.2.3.4","reason":"PutObject"}}
        """.formatted(clave));
  }
}
