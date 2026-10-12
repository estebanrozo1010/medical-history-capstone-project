package com.mh.historial.adapter.out.dynamodb;

import com.mh.historial.application.ArchivoGuardado;
import com.mh.historial.application.ArchivoRepositorio;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/** mh-files (docs/04). Sin carpeta: Query por userSub; con carpeta: Query sobre el LSI byCarpeta. */
public class DynamoArchivoRepositorio implements ArchivoRepositorio {

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
    return dynamo.queryPaginator(q.build()).items().stream()
        .map(i -> new ArchivoGuardado(i.get("userSub").s(), i.get("fileId").s(), i.get("carpeta").s(),
            i.get("nombre").s(), i.get("contentType").s(), Long.parseLong(i.get("size").n()), i.get("s3Key").s(),
            i.get("status").s(), Long.parseLong(i.get("createdAt").n())))
        .toList();
  }

  private static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  private static AttributeValue n(long v) {
    return AttributeValue.fromN(Long.toString(v));
  }
}
