package com.mh.historial.adapter.out.dynamodb;

import com.mh.historial.application.LinkGuardado;
import com.mh.historial.application.LinkRepositorio;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** mh-links (docs/04): PK token, GSI byUser (userSub, createdAt) para listar, TTL en expiresAt. */
public class DynamoLinkRepositorio implements LinkRepositorio {

  // token es palabra reservada de DynamoDB
  private static final Map<String, String> TOKEN = Map.of("#t", "token");

  private final DynamoDbClient dynamo;
  private final String tabla;

  public DynamoLinkRepositorio(DynamoDbClient dynamo, String tabla) {
    this.dynamo = dynamo;
    this.tabla = tabla;
  }

  @Override
  public void crear(LinkGuardado l) {
    dynamo.putItem(r -> r.tableName(tabla)
        .item(Map.of("token", s(l.token()), "userSub", s(l.userSub()), "fileIds", AttributeValue.fromSs(l.fileIds()),
            "activo", AttributeValue.fromBool(l.activo()), "expiresAt", n(l.expiresAt()), "createdAt", n(l.createdAt())))
        .conditionExpression("attribute_not_exists(#t)").expressionAttributeNames(TOKEN));
  }

  @Override
  public List<LinkGuardado> listar(String userSub) {
    // Un GSI no admite lectura consistente
    return dynamo.queryPaginator(r -> r.tableName(tabla).indexName("byUser").keyConditionExpression("userSub = :u")
        .expressionAttributeValues(Map.of(":u", s(userSub)))).items().stream().map(DynamoLinkRepositorio::link).toList();
  }

  @Override
  public Optional<LinkGuardado> obtener(String token) {
    Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tabla).consistentRead(true)
        .key(Map.of("token", s(token)))).item();
    return item == null || item.isEmpty() ? Optional.empty() : Optional.of(link(item));
  }

  @Override
  public boolean cambiarActivo(String token, boolean activo) {
    try {
      // La condición evita crear un ítem a medias si el TTL lo borró después de leerlo
      dynamo.updateItem(r -> r.tableName(tabla).key(Map.of("token", s(token)))
          .updateExpression("SET activo = :a").conditionExpression("attribute_exists(#t)")
          .expressionAttributeNames(TOKEN).expressionAttributeValues(Map.of(":a", AttributeValue.fromBool(activo))));
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  private static LinkGuardado link(Map<String, AttributeValue> i) {
    return new LinkGuardado(i.get("token").s(), i.get("userSub").s(), i.get("fileIds").ss(), i.get("activo").bool(),
        Long.parseLong(i.get("expiresAt").n()), Long.parseLong(i.get("createdAt").n()));
  }

  private static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  private static AttributeValue n(long v) {
    return AttributeValue.fromN(Long.toString(v));
  }
}
