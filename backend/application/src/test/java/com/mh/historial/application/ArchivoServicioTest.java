package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mh.historial.application.PerfilServicioTest.MutableClock;
import com.mh.historial.domain.Archivo;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.SolicitudSubida;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  // --- UrlDescarga ---

  @Test
  void cleanSeDescargaConUrlDeCincoMinutosYElNombreOriginalComoAdjunto() {
    guardado("f1", "examen.pdf", "CLEAN", 0);

    assertEquals("https://s3/get/sub-a/f1", servicio.urlDescarga("sub-a", "f1"));

    assertEquals("sub-a/f1|attachment; filename=\"examen.pdf\"; filename*=UTF-8''examen.pdf|PT5M", almacen.descarga);
  }

  @Test
  void noCleanNoSeDescarga() {
    guardado("f1", "a.pdf", "PENDING", reloj.segundos);
    guardado("f2", "a.pdf", "REJECTED", 0);
    guardado("f3", "a.pdf", "PENDING", 0);

    for (String fileId : new String[] {"f1", "f2", "f3"}) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.urlDescarga("sub-a", fileId));
      assertEquals(ErrorDeDominio.Tipo.CONFLICTO, e.tipo(), fileId);
    }
    assertEquals(null, almacen.descarga);
  }

  @Test
  void archivoAjenoOInexistenteNoSeEncuentra() {
    guardado("f1", "a.pdf", "CLEAN", 0);

    for (String sub : new String[] {"sub-b", "sub-a"}) {
      String fileId = sub.equals("sub-b") ? "f1" : "no-existe";
      assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO,
          assertThrows(ErrorDeDominio.class, () -> servicio.urlDescarga(sub, fileId)).tipo());
      assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO,
          assertThrows(ErrorDeDominio.class, () -> servicio.eliminar(sub, fileId)).tipo());
    }
    assertEquals(1, repo.datos.size());
    assertTrue(almacen.borrados.isEmpty());
  }

  @Test
  void disposicionConTildesComillasYEnieUsaFilenameEstrellaYRespaldoAscii() {
    assertEquals("attachment; filename=\"Examen _ano_ n.pdf\"; filename*=UTF-8''Ex%C3%A1men%20%22a%C3%B1o%22%20%C3%B1.pdf",
        ArchivoServicio.disposicion("Exámen \"año\" ñ.pdf"));
    // ; y \ no rompen el header; lo que no es ASCII imprimible queda en _
    assertEquals("attachment; filename=\"a;b_c__.pdf\"; filename*=UTF-8''a%3Bb%5Cc%E2%82%AC%F0%9F%A9%BA.pdf",
        ArchivoServicio.disposicion("a;b\\c€🩺.pdf"));
  }

  // --- EliminarArchivo ---

  @Test
  void eliminarBorraLasVersionesYDespuesElRegistroEnCualquierEstado() {
    for (String estado : new String[] {"CLEAN", "REJECTED", "PENDING"}) {
      guardado("f-" + estado, "a.pdf", estado, reloj.segundos);
      almacen.alBorrar = () -> assertEquals(1, repo.datos.size(), "el registro sigue mientras se borra el objeto");

      servicio.eliminar("sub-a", "f-" + estado);

      assertEquals("sub-a/f-" + estado, almacen.borrados.get(almacen.borrados.size() - 1));
      assertTrue(repo.datos.isEmpty(), estado);
    }
  }

  @Test
  void eliminarUnUploadExpiredSinObjetoNoFalla() {
    guardado("f1", "a.pdf", "PENDING", 0);
    assertEquals("UPLOAD_EXPIRED", servicio.listar("sub-a", null).get(0).status());

    servicio.eliminar("sub-a", "f1");

    assertTrue(repo.datos.isEmpty());
  }

  @Test
  void siLaReplicaSigueEnCursoResponde409YNoBorraElRegistro() {
    guardado("f1", "a.pdf", "CLEAN", 0);
    almacen.alBorrar = () -> {
      throw new IllegalStateException("La réplica al respaldo sigue pendiente");
    };

    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.eliminar("sub-a", "f1"));

    assertEquals(ErrorDeDominio.Tipo.CONFLICTO, e.tipo());
    assertEquals(1, repo.datos.size());
  }

  // --- Multipart ---

  static final long MB = 1024 * 1024;
  static final SolicitudSubida VIDEO = new SolicitudSubida("imagenes", "eco.mp4", "video/mp4", 40 * MB);

  @Test
  void iniciarMultipartGuardaPendingAntesDeIniciarYDevuelvePartesDe16Mb() {
    almacen.alIniciar = () -> assertEquals(1, repo.datos.size(), "el registro se crea antes de CreateMultipartUpload");

    SubidaMultipart m = servicio.iniciarMultipart("sub-a", VIDEO);

    assertEquals(new ArchivoGuardado("sub-a", m.fileId(), "imagenes", "eco.mp4", "video/mp4", 40 * MB,
        "sub-a/" + m.fileId(), "PENDING", 1_700_000_000L), repo.datos.get(0));
    assertEquals(new SubidaMultipart(m.fileId(), "up-1", 16 * MB), m);
    assertEquals(List.of("iniciar|sub-a/" + m.fileId() + "|video/mp4"), almacen.multipart);
  }

  @Test
  void urlParteFirmaElTamanoExactoDeCadaParteYLaUltimaEsElResto() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    assertEquals("https://s3/sub-a/f1?partNumber=1", servicio.urlParte("sub-a", "f1", "up-1", 1));
    servicio.urlParte("sub-a", "f1", "up-1", 3);

    assertEquals(List.of("parte|sub-a/f1|up-1|1|" + 16 * MB + "|PT10M", "parte|sub-a/f1|up-1|3|" + 8 * MB + "|PT10M"),
        almacen.multipart);
  }

  @Test
  void urlParteFueraDeRangoDaValidacion() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    for (int partNumber : new int[] {0, 4, -1}) {
      assertEquals(ErrorDeDominio.Tipo.VALIDACION,
          assertThrows(ErrorDeDominio.class, () -> servicio.urlParte("sub-a", "f1", "up-1", partNumber)).tipo());
    }
    assertTrue(almacen.multipart.isEmpty());
  }

  @Test
  void completarConTodasLasPartesEnOrdenLasUne() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);
    List<Parte> partes = List.of(new Parte(1, "\"e1\""), new Parte(2, "\"e2\""), new Parte(3, "\"e3\""));

    servicio.completarMultipart("sub-a", "f1", "up-1", partes);

    assertEquals(List.of("completar|sub-a/f1|up-1|" + partes), almacen.multipart);
  }

  @Test
  void completarConPartesQueFaltanSobranODesordenadasDaValidacion() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    for (List<Parte> partes : List.of(List.<Parte>of(), List.of(new Parte(1, "e1"), new Parte(2, "e2")),
        List.of(new Parte(1, "e1"), new Parte(3, "e3"), new Parte(2, "e2")),
        List.of(new Parte(1, "e1"), new Parte(2, "e2"), new Parte(3, "e3"), new Parte(4, "e4")),
        List.of(new Parte(1, "e1"), new Parte(2, " "), new Parte(3, "e3")))) {
      assertEquals(ErrorDeDominio.Tipo.VALIDACION, assertThrows(ErrorDeDominio.class,
          () -> servicio.completarMultipart("sub-a", "f1", "up-1", partes)).tipo(), partes.toString());
    }
    assertTrue(almacen.multipart.isEmpty());
  }

  @Test
  void abortarDescartaLaSubida() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    servicio.abortarMultipart("sub-a", "f1", "up-1");

    assertEquals(List.of("abortar|sub-a/f1|up-1"), almacen.multipart);
    assertEquals(1, repo.datos.size());
  }

  @Test
  void multipartConFileIdAjenoOInexistenteDaNoEncontrado() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    for (String sub : new String[] {"sub-b", "sub-a"}) {
      String fileId = sub.equals("sub-b") ? "f1" : "no-existe";
      for (Runnable op : multipart(sub, fileId)) {
        assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, assertThrows(ErrorDeDominio.class, op::run).tipo());
      }
    }
    assertTrue(almacen.multipart.isEmpty());
  }

  @Test
  void multipartDeUnArchivoQueNoEstaPendingDaConflicto() {
    for (String estado : new String[] {"CLEAN", "REJECTED"}) {
      guardado("f-" + estado, "eco.mp4", estado, 0, 40 * MB);
      for (Runnable op : multipart("sub-a", "f-" + estado)) {
        assertEquals(ErrorDeDominio.Tipo.CONFLICTO, assertThrows(ErrorDeDominio.class, op::run).tipo(), estado);
      }
    }
    assertTrue(almacen.multipart.isEmpty());
  }

  @Test
  void pendingDeMasDeUnaHoraSigueAceptandoPartes() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);

    servicio.urlParte("sub-a", "f1", "up-1", 1);

    assertEquals(1, almacen.multipart.size());
  }

  @Test
  void eliminarUnPendingAbortaLasSubidasEnPartesEnCursoYUnCleanNo() {
    guardado("f1", "eco.mp4", "PENDING", 0, 40 * MB);
    guardado("f2", "a.pdf", "CLEAN", 0);

    servicio.eliminar("sub-a", "f1");
    servicio.eliminar("sub-a", "f2");

    assertEquals(List.of("abortarTodas|sub-a/f1"), almacen.multipart);
    assertEquals(List.of("sub-a/f1", "sub-a/f2"), almacen.borrados);
  }

  private List<Runnable> multipart(String sub, String fileId) {
    List<Parte> partes = List.of(new Parte(1, "e1"), new Parte(2, "e2"), new Parte(3, "e3"));
    return List.of(() -> servicio.urlParte(sub, fileId, "up-1", 1),
        () -> servicio.completarMultipart(sub, fileId, "up-1", partes),
        () -> servicio.abortarMultipart(sub, fileId, "up-1"));
  }

  private void guardado(String fileId, String nombre, String status, long createdAt) {
    guardado(fileId, nombre, status, createdAt, 1);
  }

  private void guardado(String fileId, String nombre, String status, long createdAt, long size) {
    repo.crear(new ArchivoGuardado("sub-a", fileId, "otros", nombre, "application/pdf", size, "sub-a/" + fileId, status,
        createdAt));
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

    @Override
    public Optional<ArchivoGuardado> obtener(String userSub, String fileId) {
      return datos.stream().filter(a -> a.userSub().equals(userSub) && a.fileId().equals(fileId)).findFirst();
    }

    @Override
    public List<ArchivoGuardado> obtenerVarios(String userSub, List<String> fileIds) {
      // Al revés de lo pedido: BatchGetItem no garantiza el orden
      return datos.reversed().stream().filter(a -> a.userSub().equals(userSub) && fileIds.contains(a.fileId())).toList();
    }

    @Override
    public boolean cambiarEstado(String userSub, String fileId, String de, String a) {
      for (int i = 0; i < datos.size(); i++) {
        ArchivoGuardado g = datos.get(i);
        if (g.userSub().equals(userSub) && g.fileId().equals(fileId) && g.status().equals(de)) {
          datos.set(i, new ArchivoGuardado(g.userSub(), g.fileId(), g.carpeta(), g.nombre(), g.contentType(),
              g.size(), g.s3Key(), a, g.createdAt()));
          return true;
        }
      }
      return false;
    }

    @Override
    public void eliminar(String userSub, String fileId) {
      datos.removeIf(a -> a.userSub().equals(userSub) && a.fileId().equals(fileId));
    }
  }

  static class AlmacenFalso implements AlmacenDeArchivos {
    String firmado;
    String descarga;
    String leido;
    byte[] contenido = new byte[0];
    final List<String> borrados = new ArrayList<>();
    Runnable alBorrar = () -> {};

    @Override
    public UrlSubida urlSubida(String clave, String contentType, long size, Duration vigencia) {
      firmado = clave + "|" + contentType + "|" + size + "|" + vigencia;
      return new UrlSubida("https://s3/" + clave, Map.of("content-type", contentType));
    }

    @Override
    public String urlDescarga(String clave, String contentDisposition, Duration vigencia) {
      descarga = clave + "|" + contentDisposition + "|" + vigencia;
      return "https://s3/get/" + clave;
    }

    @Override
    public byte[] primerosBytes(String clave, int cantidad) {
      leido = clave + "|" + cantidad;
      return Arrays.copyOf(contenido, Math.min(cantidad, contenido.length));
    }

    @Override
    public void borrarTodasLasVersiones(String clave) {
      alBorrar.run();
      borrados.add(clave);
    }

    final List<String> multipart = new ArrayList<>();
    Runnable alIniciar = () -> {};

    @Override
    public String iniciarMultipart(String clave, String contentType) {
      alIniciar.run();
      multipart.add("iniciar|" + clave + "|" + contentType);
      return "up-1";
    }

    @Override
    public String urlParte(String clave, String uploadId, int partNumber, long size, Duration vigencia) {
      multipart.add("parte|" + clave + "|" + uploadId + "|" + partNumber + "|" + size + "|" + vigencia);
      return "https://s3/" + clave + "?partNumber=" + partNumber;
    }

    @Override
    public void completarMultipart(String clave, String uploadId, List<Parte> partes) {
      multipart.add("completar|" + clave + "|" + uploadId + "|" + partes);
    }

    @Override
    public void abortarMultipart(String clave, String uploadId) {
      multipart.add("abortar|" + clave + "|" + uploadId);
    }

    @Override
    public void abortarSubidasEnCurso(String clave) {
      multipart.add("abortarTodas|" + clave);
    }
  }
}
