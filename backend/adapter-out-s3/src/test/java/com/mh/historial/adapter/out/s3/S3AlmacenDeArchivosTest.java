package com.mh.historial.adapter.out.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mh.historial.application.UrlSubida;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3AlmacenDeArchivosTest {

  // Firmar no llama a AWS: basta con credenciales falsas
  final S3AlmacenDeArchivos almacen = new S3AlmacenDeArchivos(S3Presigner.builder()
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDFALSO", "secreto")))
      .build(), null, "mh-docs-test", null, Duration.ZERO);

  @Test
  void firmaUnPutConContentTypeContentLengthEIfNoneMatchYDevuelveSoloLoQueFijaElCliente() {
    UrlSubida u = almacen.urlSubida("sub-a/f1", "application/pdf", 1000, Duration.ofMinutes(10));

    assertTrue(u.url().startsWith("https://mh-docs-test.s3.amazonaws.com/sub-a/f1?"), u.url());
    // if-none-match: un segundo PUT con la misma URL (aún vigente) no reemplaza el archivo ya escaneado
    assertTrue(u.url().contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost%3Bif-none-match"), u.url());
    assertTrue(u.url().contains("X-Amz-Expires=600"), u.url());
    assertEquals(Map.of("content-type", "application/pdf", "if-none-match", "*"), u.headers());
  }

  @Test
  void firmaUnaParteConSuTamanoExacto() {
    String url = almacen.urlParte("sub-a/f1", "up/1+", 3, 16_777_216, Duration.ofMinutes(10));

    assertTrue(url.startsWith("https://mh-docs-test.s3.amazonaws.com/sub-a/f1?"), url);
    assertTrue(url.contains("partNumber=3"), url);
    assertTrue(url.contains("uploadId=up%2F1%2B"), url);
    // content-length firmado: S3 rechaza una parte de otro tamaño (como el PUT simple)
    assertTrue(url.contains("X-Amz-SignedHeaders=content-length%3Bhost"), url);
    assertTrue(url.contains("X-Amz-Expires=600"), url);
  }

  @Test
  void firmaUnGetConLaDisposicionEnLaQuery() {
    String url = almacen.urlDescarga("sub-a/f1", "attachment; filename=\"a n.pdf\"; filename*=UTF-8''a%20%C3%B1.pdf",
        Duration.ofMinutes(5));

    assertTrue(url.startsWith("https://mh-docs-test.s3.amazonaws.com/sub-a/f1?"), url);
    assertTrue(url.contains("X-Amz-Expires=300"), url);
    // El % del filename* va codificado otra vez dentro de la query: S3 lo devuelve tal cual en el header
    assertTrue(url.contains("response-content-disposition=attachment%3B%20filename%3D%22a%20n.pdf%22%3B%20filename%2A%3DUTF-8%27%27a%2520%25C3%25B1.pdf"), url);
  }
}
