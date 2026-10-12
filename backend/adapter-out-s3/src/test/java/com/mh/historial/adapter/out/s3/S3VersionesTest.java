package com.mh.historial.adapter.out.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.ReplicationStatus;

/** Lectura por rango y borrado de versiones de S3AlmacenDeArchivos (mh-postscan), con un S3Client falso. */
class S3VersionesTest {

  final S3Falso s3 = new S3Falso();

  @Test
  void borraTodasLasVersionesYMarcasDeLaClaveEnLosDosBucketsSinTocarOtras() {
    s3.objetos.addAll(List.of("docs|sub-a/f1|v1", "docs|sub-a/f1|v2", "docs|sub-a/f1|m1", "docs|sub-a/f10|v9",
        "respaldo|sub-a/f1|r1", "respaldo|sub-a/f1|r2", "respaldo|sub-a/f2|r3"));

    almacen(Duration.ofSeconds(5)).borrarTodasLasVersiones("sub-a/f1");

    assertEquals(List.of("docs|sub-a/f10|v9", "respaldo|sub-a/f2|r3"), s3.objetos);
    // Primero el respaldo: si algo falla a mitad, la copia original sigue para el reintento
    assertEquals(List.of("respaldo|sub-a/f1|r1", "respaldo|sub-a/f1|r2", "docs|sub-a/f1|v1", "docs|sub-a/f1|v2",
        "docs|sub-a/f1|m1"), s3.borrados);
  }

  @Test
  void esperaAQueLaReplicaTermineAntesDeBorrar() {
    s3.objetos.addAll(List.of("docs|sub-a/f1|v1"));
    s3.replica.addAll(List.of(ReplicationStatus.PENDING, ReplicationStatus.COMPLETED));

    almacen(Duration.ofSeconds(5)).borrarTodasLasVersiones("sub-a/f1");

    assertEquals(List.of("docs|sub-a/f1|v1", "docs|sub-a/f1|v1"), s3.consultadas);
    assertEquals(List.of("docs|sub-a/f1|v1"), s3.borrados);
  }

  @Test
  void siLaReplicaSiguePendienteFallaSinBorrarNadaParaQueSeReintente() {
    s3.objetos.addAll(List.of("docs|sub-a/f1|v1"));
    s3.replica.add(ReplicationStatus.PENDING);

    assertThrows(IllegalStateException.class, () -> almacen(Duration.ZERO).borrarTodasLasVersiones("sub-a/f1"));

    assertEquals(List.of(), s3.borrados);
  }

  @Test
  void primerosBytesPideUnRangoYDevuelveVacioSiElObjetoNoExiste() {
    s3.objetos.add("docs|sub-a/f1|v1");

    assertArrayEquals(new byte[] {1, 2}, almacen(Duration.ZERO).primerosBytes("sub-a/f1", 12));
    assertEquals("docs|sub-a/f1|bytes=0-11", s3.rango);
    assertArrayEquals(new byte[0], almacen(Duration.ZERO).primerosBytes("sub-a/no-existe", 12));
  }

  private S3AlmacenDeArchivos almacen(Duration esperaReplica) {
    return new S3AlmacenDeArchivos(null, s3, "docs", "respaldo", esperaReplica);
  }

  /** Objetos como "bucket|clave|versionId"; los versionId que empiezan con m son marcas de borrado. */
  static class S3Falso implements S3Client {
    final List<String> objetos = new ArrayList<>();
    final List<String> borrados = new ArrayList<>();
    final List<String> consultadas = new ArrayList<>();
    final Deque<ReplicationStatus> replica = new ArrayDeque<>();
    String rango;

    @Override
    public ListObjectVersionsResponse listObjectVersions(ListObjectVersionsRequest r) {
      List<String[]> deLaClave = objetos.stream().map(o -> o.split("\\|"))
          .filter(o -> o[0].equals(r.bucket()) && o[1].startsWith(r.prefix())).toList();
      return ListObjectVersionsResponse.builder().isTruncated(false)
          .versions(deLaClave.stream().filter(o -> !o[2].startsWith("m"))
              .map(o -> ObjectVersion.builder().key(o[1]).versionId(o[2]).build()).toList())
          .deleteMarkers(deLaClave.stream().filter(o -> o[2].startsWith("m"))
              .map(o -> DeleteMarkerEntry.builder().key(o[1]).versionId(o[2]).build()).toList())
          .build();
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest r) {
      consultadas.add(r.bucket() + "|" + r.key() + "|" + r.versionId());
      ReplicationStatus estado = replica.size() > 1 ? replica.poll() : replica.peek();
      return HeadObjectResponse.builder().replicationStatus(estado == null ? ReplicationStatus.COMPLETED : estado).build();
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest r) {
      String o = r.bucket() + "|" + r.key() + "|" + r.versionId();
      borrados.add(o);
      objetos.remove(o);
      return DeleteObjectResponse.builder().build();
    }

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest r) {
      rango = r.bucket() + "|" + r.key() + "|" + r.range();
      if (objetos.stream().noneMatch(o -> o.startsWith(r.bucket() + "|" + r.key() + "|"))) {
        throw NoSuchKeyException.builder().message("no existe").build();
      }
      return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1, 2});
    }

    @Override
    public String serviceName() {
      return "s3";
    }

    @Override
    public void close() {}
  }
}
