package com.mh.historial.adapter.out.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mh.historial.application.Parte;
import com.mh.historial.domain.ErrorDeDominio;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Subida en partes de S3AlmacenDeArchivos con un S3Client falso. */
class S3MultipartTest {

  final S3Falso s3 = new S3Falso();
  final S3AlmacenDeArchivos almacen = new S3AlmacenDeArchivos(null, s3, "docs", "respaldo", Duration.ZERO);

  @Test
  void iniciarCreaLaSubidaEnElBucketConElTipoDeclarado() {
    assertEquals("up-1", almacen.iniciarMultipart("sub-a/f1", "video/mp4"));

    assertEquals(List.of("crear|docs|sub-a/f1|video/mp4"), s3.llamadas);
  }

  @Test
  void completarUneLasPartesSinReemplazarUnObjetoExistente() {
    almacen.completarMultipart("sub-a/f1", "up-1", List.of(new Parte(1, "\"e1\""), new Parte(2, "\"e2\"")));

    assertEquals(List.of("completar|docs|sub-a/f1|up-1|*|1=\"e1\",2=\"e2\""), s3.llamadas);
  }

  @Test
  void erroresDeS3AlCompletarSeVuelvenErroresDeDominio() {
    for (Object[] caso : new Object[][] {{404, ErrorDeDominio.Tipo.NO_ENCONTRADO}, {400, ErrorDeDominio.Tipo.VALIDACION},
        {412, ErrorDeDominio.Tipo.CONFLICTO}, {409, ErrorDeDominio.Tipo.CONFLICTO}}) {
      s3.falla = (S3Exception) S3Exception.builder().statusCode((int) caso[0]).message("x").build();

      ErrorDeDominio e = assertThrows(ErrorDeDominio.class,
          () -> almacen.completarMultipart("sub-a/f1", "up-1", List.of(new Parte(1, "e1"))));

      assertEquals(caso[1], e.tipo(), "status " + caso[0]);
    }
  }

  @Test
  void otroErrorDeS3AlCompletarSePropaga() {
    s3.falla = (S3Exception) S3Exception.builder().statusCode(500).message("x").build();

    assertThrows(S3Exception.class, () -> almacen.completarMultipart("sub-a/f1", "up-1", List.of(new Parte(1, "e1"))));
  }

  @Test
  void abortarEsIdempotente() {
    almacen.abortarMultipart("sub-a/f1", "up-1");
    s3.falla = NoSuchUploadException.builder().statusCode(404).message("no existe").build();
    almacen.abortarMultipart("sub-a/f1", "up-1");

    assertEquals(List.of("abortar|docs|sub-a/f1|up-1", "abortar|docs|sub-a/f1|up-1"), s3.llamadas);
  }

  @Test
  void abortarSubidasEnCursoSoloTocaLaClaveExacta() {
    s3.enCurso.addAll(List.of("sub-a/f1|up-1", "sub-a/f1|up-2", "sub-a/f10|up-3"));

    almacen.abortarSubidasEnCurso("sub-a/f1");

    assertEquals(List.of("listar|docs|sub-a/f1", "abortar|docs|sub-a/f1|up-1", "abortar|docs|sub-a/f1|up-2"),
        s3.llamadas);
  }

  /** Subidas en curso como "clave|uploadId"; `falla` se lanza en la próxima llamada que la use. */
  static class S3Falso implements S3Client {
    final List<String> llamadas = new ArrayList<>();
    final List<String> enCurso = new ArrayList<>();
    S3Exception falla;

    @Override
    public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest r) {
      llamadas.add("crear|" + r.bucket() + "|" + r.key() + "|" + r.contentType());
      return CreateMultipartUploadResponse.builder().uploadId("up-1").build();
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(CompleteMultipartUploadRequest r) {
      fallarSiToca();
      llamadas.add("completar|" + r.bucket() + "|" + r.key() + "|" + r.uploadId() + "|" + r.ifNoneMatch() + "|"
          + String.join(",", r.multipartUpload().parts().stream().map(p -> p.partNumber() + "=" + p.eTag()).toList()));
      return CompleteMultipartUploadResponse.builder().build();
    }

    @Override
    public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest r) {
      llamadas.add("abortar|" + r.bucket() + "|" + r.key() + "|" + r.uploadId());
      fallarSiToca();
      return AbortMultipartUploadResponse.builder().build();
    }

    @Override
    public ListMultipartUploadsResponse listMultipartUploads(ListMultipartUploadsRequest r) {
      llamadas.add("listar|" + r.bucket() + "|" + r.prefix());
      return ListMultipartUploadsResponse.builder().isTruncated(false)
          .uploads(enCurso.stream().map(u -> u.split("\\|")).filter(u -> u[0].startsWith(r.prefix()))
              .map(u -> MultipartUpload.builder().key(u[0]).uploadId(u[1]).build()).toList())
          .build();
    }

    private void fallarSiToca() {
      if (falla != null) {
        S3Exception e = falla;
        falla = null;
        throw e;
      }
    }

    @Override
    public String serviceName() {
      return "s3";
    }

    @Override
    public void close() {}
  }
}
