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
      .build(), "mh-docs-test");

  @Test
  void firmaUnPutConContentTypeYContentLengthYDevuelveSoloLoQueFijaElCliente() {
    UrlSubida u = almacen.urlSubida("sub-a/f1", "application/pdf", 1000, Duration.ofMinutes(10));

    assertTrue(u.url().startsWith("https://mh-docs-test.s3.amazonaws.com/sub-a/f1?"), u.url());
    assertTrue(u.url().contains("X-Amz-SignedHeaders=content-length%3Bcontent-type%3Bhost"), u.url());
    assertTrue(u.url().contains("X-Amz-Expires=600"), u.url());
    assertEquals(Map.of("content-type", "application/pdf"), u.headers());
  }
}
