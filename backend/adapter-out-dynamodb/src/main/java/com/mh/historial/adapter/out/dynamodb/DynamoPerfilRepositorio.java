package com.mh.historial.adapter.out.dynamodb;

import com.mh.historial.application.PerfilGuardado;
import com.mh.historial.application.PerfilRepositorio;
import com.mh.historial.domain.ErrorDeDominio;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** mh-profiles (docs/04). Escrituras condicionales: crear no pisa, actualizar no inventa. */
public class DynamoPerfilRepositorio implements PerfilRepositorio {

  private final DynamoDbClient dynamo;
  private final String tabla;

  public DynamoPerfilRepositorio(DynamoDbClient dynamo, String tabla) {
    this.dynamo = dynamo;
    this.tabla = tabla;
  }

  @Override
  public Optional<PerfilGuardado> buscar(String sub) {
    // Lectura consistente: el GET justo después del POST ya lo ve
    Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(tabla).key(clave(sub)).consistentRead(true)).item();
    if (item == null || item.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new PerfilGuardado(item.get("sub").s(), item.get("email").s(), item.get("nombre").s(),
        item.get("cedulaEnc").b().asByteArray(), item.get("fechaNacimientoEnc").b().asByteArray(),
        item.get("genero").s(), Long.parseLong(item.get("createdAt").n()), Long.parseLong(item.get("updatedAt").n())));
  }

  @Override
  public void crear(PerfilGuardado p) {
    try {
      dynamo.putItem(r -> r.tableName(tabla)
          .item(Map.of("sub", s(p.sub()), "email", s(p.email()), "nombre", s(p.nombre()),
              "cedulaEnc", b(p.cedulaEnc()), "fechaNacimientoEnc", b(p.fechaNacimientoEnc()),
              "genero", s(p.genero()), "createdAt", n(p.createdAt()), "updatedAt", n(p.updatedAt())))
          .conditionExpression("attribute_not_exists(#sub)")
          .expressionAttributeNames(Map.of("#sub", "sub")));
    } catch (ConditionalCheckFailedException e) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "El perfil ya existe");
    }
  }

  @Override
  public void actualizar(PerfilGuardado p) {
    try {
      dynamo.updateItem(r -> r.tableName(tabla).key(clave(p.sub()))
          .updateExpression("SET #email = :email, #nombre = :nombre, #cedula = :cedula, "
              + "#fecha = :fecha, #genero = :genero, #updatedAt = :updatedAt")
          .conditionExpression("attribute_exists(#sub)")
          .expressionAttributeNames(Map.of("#sub", "sub", "#email", "email", "#nombre", "nombre",
              "#cedula", "cedulaEnc", "#fecha", "fechaNacimientoEnc", "#genero", "genero", "#updatedAt", "updatedAt"))
          .expressionAttributeValues(Map.of(":email", s(p.email()), ":nombre", s(p.nombre()),
              ":cedula", b(p.cedulaEnc()), ":fecha", b(p.fechaNacimientoEnc()), ":genero", s(p.genero()),
              ":updatedAt", n(p.updatedAt()))));
    } catch (ConditionalCheckFailedException e) {
      throw new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "El perfil no existe");
    }
  }

  private static Map<String, AttributeValue> clave(String sub) {
    return Map.of("sub", s(sub));
  }

  private static AttributeValue s(String v) {
    return AttributeValue.fromS(v);
  }

  private static AttributeValue b(byte[] v) {
    return AttributeValue.fromB(SdkBytes.fromByteArray(v));
  }

  private static AttributeValue n(long v) {
    return AttributeValue.fromN(Long.toString(v));
  }
}
