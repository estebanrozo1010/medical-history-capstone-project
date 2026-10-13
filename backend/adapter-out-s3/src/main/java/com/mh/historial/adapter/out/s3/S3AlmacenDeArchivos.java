package com.mh.historial.adapter.out.s3;

import com.mh.historial.application.AlmacenDeArchivos;
import com.mh.historial.application.UrlSubida;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.ReplicationStatus;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Bucket mh-docs y su respaldo: URLs firmadas, lectura por rango y borrado de versiones (docs/02). */
public class S3AlmacenDeArchivos implements AlmacenDeArchivos {

  private static final Duration PAUSA_REPLICA = Duration.ofSeconds(1);

  private final S3Presigner presigner;
  private final S3Client s3;
  private final String bucket;
  private final String respaldo;
  private final Duration esperaReplica;

  public S3AlmacenDeArchivos(S3Presigner presigner, S3Client s3, String bucket, String respaldo,
      Duration esperaReplica) {
    this.presigner = presigner;
    this.s3 = s3;
    this.bucket = bucket;
    this.respaldo = respaldo;
    this.esperaReplica = esperaReplica;
  }

  @Override
  public UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia) {
    PresignedPutObjectRequest p = presigner.presignPutObject(r -> r.signatureDuration(vigencia)
        // If-None-Match: * hace que S3 rechace (412) un segundo PUT con la misma URL sobre el archivo ya escaneado
        .putObjectRequest(o -> o.bucket(bucket).key(clave).contentType(contentType).contentLength(size)
            .ifNoneMatch("*")));
    // host y content-length van firmados pero los pone el cliente HTTP (el navegador no deja fijarlos)
    Map<String, String> headers = new HashMap<>();
    p.signedHeaders().forEach((nombre, valores) -> {
      if (!nombre.equalsIgnoreCase("host") && !nombre.equalsIgnoreCase("content-length")) {
        headers.put(nombre, String.join(",", valores));
      }
    });
    return new UrlSubida(p.url().toString(), headers);
  }

  @Override
  public String urlDescarga(String clave, String contentDisposition, Duration vigencia) {
    return presigner.presignGetObject(r -> r.signatureDuration(vigencia)
        .getObjectRequest(o -> o.bucket(bucket).key(clave).responseContentDisposition(contentDisposition)))
        .url().toString();
  }

  @Override
  public byte[] primerosBytes(String clave, int cantidad) {
    try {
      return s3.getObjectAsBytes(r -> r.bucket(bucket).key(clave).range("bytes=0-" + (cantidad - 1))).asByteArray();
    } catch (NoSuchKeyException e) {
      // Un reintento tras un borrado a medias encuentra el objeto ya borrado
      return new byte[0];
    }
  }

  /**
   * La réplica copia el objeto al respaldo de forma asíncrona (menos de 1 min en el spike). Si se borrara antes de que
   * termine, la copia podría llegar después y quedar en el respaldo; por eso se espera a que ninguna versión siga
   * PENDING y, si se agota la espera, se falla sin borrar para que el reintento de la Lambda lo vuelva a intentar.
   */
  @Override
  public void borrarTodasLasVersiones(String clave) {
    for (ListObjectVersionsResponse pagina : s3.listObjectVersionsPaginator(r -> r.bucket(bucket).prefix(clave))) {
      for (ObjectVersion v : pagina.versions()) {
        if (v.key().equals(clave)) {
          esperarReplica(clave, v.versionId());
        }
      }
    }
    borrar(respaldo, clave);
    borrar(bucket, clave);
  }

  private void esperarReplica(String clave, String versionId) {
    long limite = System.nanoTime() + esperaReplica.toNanos();
    while (s3.headObject(r -> r.bucket(bucket).key(clave).versionId(versionId)).replicationStatus()
        == ReplicationStatus.PENDING) {
      if (System.nanoTime() >= limite) {
        throw new IllegalStateException("La réplica al respaldo sigue pendiente");
      }
      try {
        Thread.sleep(PAUSA_REPLICA);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Espera de la réplica interrumpida", e);
      }
    }
  }

  private void borrar(String b, String clave) {
    for (ListObjectVersionsResponse pagina : s3.listObjectVersionsPaginator(r -> r.bucket(b).prefix(clave))) {
      // El prefijo también trae claves más largas; solo se borra la exacta
      pagina.versions().stream().filter(v -> v.key().equals(clave))
          .forEach(v -> s3.deleteObject(r -> r.bucket(b).key(clave).versionId(v.versionId())));
      pagina.deleteMarkers().stream().filter(m -> m.key().equals(clave))
          .forEach(m -> s3.deleteObject(r -> r.bucket(b).key(clave).versionId(m.versionId())));
    }
  }
}
