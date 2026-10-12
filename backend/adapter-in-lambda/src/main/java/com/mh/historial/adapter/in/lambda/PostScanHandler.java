package com.mh.historial.adapter.in.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.jr.ob.JSON;
import com.mh.historial.adapter.out.dynamodb.DynamoArchivoRepositorio;
import com.mh.historial.adapter.out.s3.S3AlmacenDeArchivos;
import com.mh.historial.application.PostScanServicio;
import com.mh.historial.application.ValidadorDeTipo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Lambda mh-postscan: regla de EventBridge "Object Created" del bucket mh-docs (infra/api.yaml). Valida el objeto con
 * el puerto ArchivoScanner (PoC: ValidadorDeTipo) y deja el registro en CLEAN o REJECTED (docs/02).
 */
public class PostScanHandler implements RequestHandler<Map<String, Object>, String> {

  // Menos que el timeout de la Lambda (60 s); si se agota, la invocación falla y Lambda la reintenta
  private static final Duration ESPERA_REPLICA = Duration.ofSeconds(40);

  private final PostScanServicio servicio;

  /** Lambda lo instancia en el init, así los clientes de AWS quedan en el snapshot de SnapStart. */
  public PostScanHandler() {
    // Sin presigner: mh-postscan no firma URLs
    this(new S3AlmacenDeArchivos(null,
        S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
        System.getenv("DOCS_BUCKET"), System.getenv("BACKUP_BUCKET"), ESPERA_REPLICA));
  }

  private PostScanHandler(S3AlmacenDeArchivos almacen) {
    this(new PostScanServicio(
        new DynamoArchivoRepositorio(
            DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create()).build(),
            System.getenv("FILES_TABLE")),
        new ValidadorDeTipo(almacen),
        almacen));
    // Priming de SnapStart sin escribir nada: GET de un objeto inexistente y GetItem de un registro inexistente
    almacen.primerosBytes("snapstart/priming", 1);
    servicio.procesar("snapstart-priming/00000000-0000-0000-0000-000000000000");
  }

  PostScanHandler(PostScanServicio servicio) {
    this.servicio = servicio;
  }

  @Override
  public String handleRequest(Map<String, Object> evento, Context context) {
    String clave = evento.get("detail") instanceof Map<?, ?> d && d.get("object") instanceof Map<?, ?> o
        && o.get("key") instanceof String k ? k : null;
    try {
      String resultado = servicio.procesar(clave);
      // Sin nombre ni carpeta: solo la clave {sub}/{fileId}, que no es un dato personal
      log("INFO", "\"clave\":" + texto(clave) + ",\"resultado\":" + texto(resultado));
      return resultado;
    } catch (RuntimeException e) {
      log("ERROR", "\"clave\":" + texto(clave) + ",\"error\":"
          + texto(e instanceof SdkException || e instanceof IllegalStateException ? e.toString() : e.getClass().getName()));
      // Lambda reintenta la invocación asíncrona; el servicio es idempotente
      throw e;
    }
  }

  private static void log(String nivel, String campos) {
    System.out.println("{\"level\":\"" + nivel + "\"," + campos + "}");
  }

  private static String texto(Object valor) {
    try {
      return JSON.std.asString(valor);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
