package com.mh.historial.adapter.out.s3;

import com.mh.historial.application.AlmacenDeArchivos;
import com.mh.historial.application.UrlSubida;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Bucket mh-docs: URLs firmadas (docs/02). El cifrado KMS lo aplica el bucket por defecto. */
public class S3AlmacenDeArchivos implements AlmacenDeArchivos {

  private final S3Presigner presigner;
  private final String bucket;

  public S3AlmacenDeArchivos(S3Presigner presigner, String bucket) {
    this.presigner = presigner;
    this.bucket = bucket;
  }

  @Override
  public UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia) {
    PresignedPutObjectRequest p = presigner.presignPutObject(r -> r.signatureDuration(vigencia)
        .putObjectRequest(o -> o.bucket(bucket).key(clave).contentType(contentType).contentLength(size)));
    // host y content-length van firmados pero los pone el cliente HTTP (el navegador no deja fijarlos)
    Map<String, String> headers = new HashMap<>();
    p.signedHeaders().forEach((nombre, valores) -> {
      if (!nombre.equalsIgnoreCase("host") && !nombre.equalsIgnoreCase("content-length")) {
        headers.put(nombre, String.join(",", valores));
      }
    });
    return new UrlSubida(p.url().toString(), headers);
  }
}
