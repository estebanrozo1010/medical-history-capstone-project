package com.mh.historial.adapter.out.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mh.historial.application.ArchivoGuardado;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/** obtenerVarios (BatchGetItem de mh-links) con un DynamoDbClient falso. */
class DynamoArchivoRepositorioTest {

  final DynamoFalso dynamo = new DynamoFalso();
  final DynamoArchivoRepositorio repo = new DynamoArchivoRepositorio(dynamo, "mh-files");

  @Test
  void pideLasClavesDelUsuarioConLecturaConsistenteYDevuelveLasQueExisten() {
    dynamo.respuestas.add(respuesta(List.of(item("f2")), Map.of()));

    List<ArchivoGuardado> r = repo.obtenerVarios("sub-a", List.of("f1", "f2"));

    assertEquals(List.of("f2"), r.stream().map(ArchivoGuardado::fileId).toList());
    assertEquals(new ArchivoGuardado("sub-a", "f2", "otros", "f2.pdf", "application/pdf", 7, "sub-a/f2", "CLEAN", 9),
        r.get(0));
    KeysAndAttributes pedido = dynamo.pedidos.get(0).requestItems().get("mh-files");
    assertTrue(pedido.consistentRead());
    assertEquals(List.of(clave("f1"), clave("f2")), pedido.keys());
  }

  @Test
  void reintentaSoloLasUnprocessedKeys() {
    KeysAndAttributes sinProcesar = KeysAndAttributes.builder().consistentRead(true).keys(clave("f2")).build();
    dynamo.respuestas.add(respuesta(List.of(item("f1")), Map.of("mh-files", sinProcesar)));
    dynamo.respuestas.add(respuesta(List.of(item("f2")), Map.of()));

    List<ArchivoGuardado> r = repo.obtenerVarios("sub-a", List.of("f1", "f2"));

    assertEquals(List.of("f1", "f2"), r.stream().map(ArchivoGuardado::fileId).toList());
    assertEquals(2, dynamo.pedidos.size());
    assertEquals(List.of(clave("f2")), dynamo.pedidos.get(1).requestItems().get("mh-files").keys());
  }

  @Test
  void siSiguenSinProcesarseTrasLosReintentosFallaEnVezDeDevolverUnaListaIncompleta() {
    KeysAndAttributes sinProcesar = KeysAndAttributes.builder().keys(clave("f1")).build();
    for (int i = 0; i < 10; i++) {
      dynamo.respuestas.add(respuesta(List.of(), Map.of("mh-files", sinProcesar)));
    }

    assertThrows(IllegalStateException.class, () -> repo.obtenerVarios("sub-a", List.of("f1")));
    assertEquals(5, dynamo.pedidos.size());
  }

  @Test
  void sinFileIdsNoLlamaADynamo() {
    assertEquals(List.of(), repo.obtenerVarios("sub-a", List.of()));
    assertEquals(0, dynamo.pedidos.size());
  }

  static Map<String, AttributeValue> clave(String fileId) {
    return Map.of("userSub", AttributeValue.fromS("sub-a"), "fileId", AttributeValue.fromS(fileId));
  }

  static Map<String, AttributeValue> item(String fileId) {
    return Map.of("userSub", AttributeValue.fromS("sub-a"), "fileId", AttributeValue.fromS(fileId),
        "carpeta", AttributeValue.fromS("otros"), "nombre", AttributeValue.fromS(fileId + ".pdf"),
        "contentType", AttributeValue.fromS("application/pdf"), "size", AttributeValue.fromN("7"),
        "s3Key", AttributeValue.fromS("sub-a/" + fileId), "status", AttributeValue.fromS("CLEAN"),
        "createdAt", AttributeValue.fromN("9"));
  }

  static BatchGetItemResponse respuesta(List<Map<String, AttributeValue>> items,
      Map<String, KeysAndAttributes> sinProcesar) {
    return BatchGetItemResponse.builder().responses(Map.of("mh-files", items)).unprocessedKeys(sinProcesar).build();
  }

  static class DynamoFalso implements DynamoDbClient {
    final Deque<BatchGetItemResponse> respuestas = new ArrayDeque<>();
    final List<BatchGetItemRequest> pedidos = new ArrayList<>();

    @Override
    public BatchGetItemResponse batchGetItem(BatchGetItemRequest r) {
      pedidos.add(r);
      return respuestas.removeFirst();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}
  }
}
