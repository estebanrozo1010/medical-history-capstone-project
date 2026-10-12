package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mh.historial.application.PerfilServicioTest.MutableClock;
import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.SolicitudSubida;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArchivoServicioTest {

  static final SolicitudSubida PDF = new SolicitudSubida("cardiologia", "examen.pdf", "application/pdf", 1000);

  final RepoEnMemoria repo = new RepoEnMemoria();
  final AlmacenFalso almacen = new AlmacenFalso();
  final MutableClock reloj = new MutableClock(1_700_000_000L);
  final ArchivoServicio servicio = new ArchivoServicio(repo, almacen, reloj);

  // --- SolicitarSubida ---

  @Test
  void solicitarSubidaGuardaPendingConClaveSubYFileId() {
    SubidaAutorizada s = servicio.solicitarSubida("sub-a", PDF);

    UUID.fromString(s.fileId());
    ArchivoGuardado g = repo.datos.get(0);
    assertEquals(new ArchivoGuardado("sub-a", s.fileId(), "cardiologia", "examen.pdf", "application/pdf", 1000,
        "sub-a/" + s.fileId(), "PENDING", 1_700_000_000L), g);
  }

  @Test
  void solicitarSubidaFirmaUnPutDeDiezMinutosConTipoYTamano() {
    SubidaAutorizada s = servicio.solicitarSubida("sub-a", PDF);

    assertEquals("sub-a/" + s.fileId() + "|application/pdf|1000|PT10M", almacen.firmado);
    assertEquals("https://s3/sub-a/" + s.fileId(), s.url());
    assertEquals(Map.of("content-type", "application/pdf"), s.headers());
    assertEquals(600, s.expiresIn());
  }

  @Test
  void cadaSubidaTieneSuPropioFileId() {
    assertNotEquals(servicio.solicitarSubida("sub-a", PDF).fileId(), servicio.solicitarSubida("sub-a", PDF).fileId());
  }

  // --- ListarArchivos ---

  @Test
  void listarDevuelveSoloLosDelUsuarioDelMasNuevoAlMasViejo() {
    String viejo = servicio.solicitarSubida("sub-a", PDF).fileId();
    reloj.segundos += 10;
    String nuevo = servicio.solicitarSubida("sub-a", new SolicitudSubida("otros", "foto.png", "image/png", 5)).fileId();
    servicio.solicitarSubida("sub-b", PDF);

    List<Archivo> archivos = servicio.listar("sub-a", null);

    assertEquals(List.of(nuevo, viejo), archivos.stream().map(Archivo::fileId).toList());
    assertEquals(new Archivo(viejo, "cardiologia", "examen.pdf", "application/pdf", 1000, "PENDING", 1_700_000_000L),
        archivos.get(1));
  }

  @Test
  void listarConCarpetaFiltra() {
    servicio.solicitarSubida("sub-a", PDF);
    String foto = servicio.solicitarSubida("sub-a", new SolicitudSubida("otros", "foto.png", "image/png", 5)).fileId();

    assertEquals(List.of(foto), servicio.listar("sub-a", "otros").stream().map(Archivo::fileId).toList());
    assertEquals(List.of(), servicio.listar("sub-a", "pediatria"));
  }

  @Test
  void listarConCarpetaInvalidaDaValidacion() {
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.listar("sub-a", "secreta"));
    assertEquals(ErrorDeDominio.Tipo.VALIDACION, e.tipo());
  }

  @Test
  void pendingDeMasDeUnaHoraSeVeComoUploadExpiredSinGuardarlo() {
    servicio.solicitarSubida("sub-a", PDF);

    reloj.segundos += 3600;
    assertEquals("PENDING", servicio.listar("sub-a", null).get(0).status());
    reloj.segundos += 1;
    assertEquals("UPLOAD_EXPIRED", servicio.listar("sub-a", null).get(0).status());
    assertEquals("PENDING", repo.datos.get(0).status());
  }

  @Test
  void otrosEstadosNoVencen() {
    repo.crear(new ArchivoGuardado("sub-a", "f1", "otros", "a.pdf", "application/pdf", 1, "sub-a/f1", "CLEAN", 0));

    assertEquals("CLEAN", servicio.listar("sub-a", null).get(0).status());
  }

  static class RepoEnMemoria implements ArchivoRepositorio {
    final List<ArchivoGuardado> datos = new ArrayList<>();

    @Override
    public void crear(ArchivoGuardado a) {
      datos.add(a);
    }

    @Override
    public List<ArchivoGuardado> listar(String userSub, String carpeta) {
      return datos.stream()
          .filter(a -> a.userSub().equals(userSub) && (carpeta == null || a.carpeta().equals(carpeta)))
          .toList();
    }
  }

  static class AlmacenFalso implements AlmacenDeArchivos {
    String firmado;

    @Override
    public UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia) {
      firmado = clave + "|" + contentType + "|" + size + "|" + vigencia;
      return new UrlSubida("https://s3/" + clave, Map.of("content-type", contentType));
    }
  }
}
