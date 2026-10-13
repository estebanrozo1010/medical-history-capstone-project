package com.mh.historial.adapter.out.dynamodb;

import com.mh.historial.application.ArchivoGuardado;
import com.mh.historial.application.ArchivoRepositorio;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/** mh-files (docs/04). Sin carpeta: Query por userSub; con carpeta: Query sobre el LSI byCarpeta. */
public class DynamoArchivoRepositorio implements ArchivoRepositorio {

  // UnprocessedKeys: hasta 4 reintentos con espera de 50, 100, 200 y 400 ms (750 ms en total)
  private static final int REINTENTOS = 4;

  private final DynamoDbClient dynamo;
  private final String tabla;

  public DynamoArchivoRepositorio(DynamoDbClient dynamo, String tabla) {
    this.dynamo = dynamo;
    this.tabla = tabla;
  }

  @Override
  public void crear(ArchivoGuardado a) {
    // fileId es un UUID nuevo; la condición solo evita pisar una fila si alguna vez se repitiera
    dynamo.putItem(r -> r.tableName(tabla)
        .item(Map.of("userSub", s(a.userSub()), "fileId", s(a.fileId()), "carpeta", s(a.carpeta()),
            "nombre", s(a.nombre()), "contentType", s(a.contentType()), "size", n(a.size()),
            "s3Key", s(a.s3Key()), "status", s(a.status()), "createdAt", n(a.createdAt())))
        .conditionExpression("attribute_not_exists(fileId)"));
  }

  @Override
  public List<ArchivoGuardado> listar(String userSub, String carpeta) {
    // Lectura consistente (también vale en un LSI): el archivo recién pedido ya aparece
    QueryRequest.Builder q = QueryRequest.builder().tableName(tabla).consistentRead(true);
    if (carpeta == null) {
      q.keyConditionExpression("userSub = :u").expressionAttributeValues(Map.of(":u", s(userSub)));
    } else {
      q.indexName("byCarpeta").keyConditionExpression("userSub = :u AND carpeta = :c")
          .expressionAttributeValues(Map.of(":u", s(userSub), ":c", s(carpeta)));
    }
    return dynamo.queryPaginator(q.build()).items().stream().map(DynamoArchivoRepositorio::archivo).toList();
  }

  @Override
  public Optional<ArchivoGuardado> obtener(String userSub, String fileId) {
    Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tabla).consistentRead(true)
        .key(Map.of("userSub", s(userSub), "fileId", s(fileId)))).item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(archivo(item));
  }

  @Override
  public List<ArchivoGuardado> obtenerVarios(String userSub, List<String> fileIds) {
    if (fileIds.isEmpty()) {
      return List.of();
    }
    // Consistente: un archivo recién eliminado o rechazado ya no aparece en el link
    Map<String, KeysAndAttributes> pendientes = Map.of(tabla, KeysAndAttributes.builder().consistentRead(true)
        .keys(fileIds.stream().map(id -> Map.of("userSub", s(userSub), "fileId", s(id))).toList()).build());
    List<ArchivoGuardado> encontrados = new ArrayList<>();
    for (int intento = 0; ; intento++) {
      BatchGetItemResponse r = dynamo.batchGetItem(BatchGetItemRequest.builder().requestItems(pendientes).build());
      r.responses().getOrDefault(tabla, List.of()).forEach(i -> encontrados.add(archivo(i)));
      pendientes = r.unprocessedKeys();
      if (pendientes.isEmpty()) {
        return encontrados;
      }
      if (intento == REINTENTOS) {
        // Mejor un 500 que una lista incompleta que parezca la definitiva
        throw new IllegalStateException("BatchGetItem dejó claves sin procesar tras " + REINTENTOS + " reintentos");
      }
      try {
        Thread.sleep(50L << intento);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Override
  public boolean cambiarEstado(String userSub, String fileId, String de, String a) {
    try {
      // status es palabra reservada de DynamoDB
      dynamo.updateItem(r -> r.tableName(tabla).key(Map.of("userSub", s(userSub), "fileId", s(fileId)))
          .updateExpression("SET #s = :a").conditionExpression("#s = :de")
          .expressionAttributeNames(Map.of("#s", "status"))
          .expressionAttributeValues(Map.of(":a", s(a), ":de", s(de))));
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public void eliminar(String userSub, String fileId) {
    dynamo.deleteItem(r -> r.tableName(tabla).key(Map.of("userSub", s(userSub), "fileId", s(fileId))));
  }

  private static ArchivoGuardado archivo(Map<String, AttributeValue> i) {
    return new ArchivoGuardado(i.get("userSub").s(), i.get("fileId").s(), i.get("carpeta").s(),
        i.get("nombre").s(), i.get("contentType").s(), Long.parseLong(i.get("size").n()), i.get("s3Key").s(),
        i.get("status").s(), Long.parseLong(i.get("createdAt").n()));
  }

  private static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  private static AttributeValue n(long v) {
    return AttributeValue.fromN(Long.toString(v));
  }
}
