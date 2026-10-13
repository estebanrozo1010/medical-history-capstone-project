package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mh.historial.application.ArchivoServicioTest.AlmacenFalso;
import com.mh.historial.application.ArchivoServicioTest.RepoEnMemoria;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostScanServicioTest {

  static final String SUB = "0f1e2d3c-aaaa-bbbb-cccc-000000000001";
  static final String FILE_ID = "94531068-906b-4dd4-a029-25dc610955cc";
  static final String CLAVE = SUB + "/" + FILE_ID;

  final RepoEnMemoria repo = new RepoEnMemoria();
  final AlmacenFalso almacen = new AlmacenFalso();
  final List<String> escaneados = new ArrayList<>();
  String veredicto = "CLEAN";
  final PostScanServicio servicio = new PostScanServicio(repo, a -> {
    escaneados.add(a.s3Key());
    return veredicto;
  }, almacen);

  @Test
  void archivoValidoPasaACleanSinBorrarNada() {
    pendiente();

    assertEquals("CLEAN", servicio.procesar(CLAVE));

    assertEquals("CLEAN", repo.datos.get(0).status());
    assertTrue(almacen.borrados.isEmpty());
  }

  @Test
  void archivoRechazadoBorraTodasLasVersionesAntesDeMarcarloRejected() {
    pendiente();
    veredicto = "REJECTED";
    almacen.alBorrar = () -> assertEquals("PENDING", repo.datos.get(0).status());

    assertEquals("REJECTED", servicio.procesar(CLAVE));

    assertEquals(List.of(CLAVE), almacen.borrados);
    assertEquals("REJECTED", repo.datos.get(0).status());
  }

  @Test
  void siFallaElBorradoElRegistroSigueEnPendingParaElReintento() {
    pendiente();
    veredicto = "REJECTED";
    almacen.alBorrar = () -> {
      throw new IllegalStateException("réplica pendiente");
    };

    try {
      servicio.procesar(CLAVE);
    } catch (IllegalStateException e) {
      // la Lambda falla y EventBridge/Lambda reintentan
    }

    assertEquals("PENDING", repo.datos.get(0).status());
  }

  @Test
  void reenviarElEventoNoHaceNada() {
    pendiente();
    servicio.procesar(CLAVE);

    assertEquals("SIN_CAMBIOS", servicio.procesar(CLAVE));

    assertEquals(1, escaneados.size());
    assertEquals("CLEAN", repo.datos.get(0).status());
  }

  @Test
  void unEventoDeUnRechazadoVuelveABorrarSinEscanearNiFallar() {
    pendiente();
    veredicto = "REJECTED";
    servicio.procesar(CLAVE);

    // Reenvío, o un PUT repetido con la URL aún vigente que recreó el objeto tras el borrado
    assertEquals("SIN_CAMBIOS", servicio.procesar(CLAVE));

    assertEquals(List.of(CLAVE, CLAVE), almacen.borrados);
    assertEquals(1, escaneados.size());
    assertEquals("REJECTED", repo.datos.get(0).status());
  }

  @Test
  void siOtroProcesoYaCambioElEstadoNoLoPisa() {
    pendiente();
    // Entre la lectura y la escritura otro evento marcó el archivo
    PostScanServicio carrera = new PostScanServicio(repo, a -> {
      repo.cambiarEstado(SUB, FILE_ID, "PENDING", "REJECTED");
      return "CLEAN";
    }, almacen);

    assertEquals("SIN_CAMBIOS", carrera.procesar(CLAVE));

    assertEquals("REJECTED", repo.datos.get(0).status());
  }

  @Test
  void objetoSinRegistroEnMhFilesSeBorraSinEscanear() {
    // El registro se crea antes de firmar la subida: sin registro = archivo eliminado (un PUT tardío con la URL vigente)
    assertEquals("HUERFANO_BORRADO", servicio.procesar(CLAVE));

    assertTrue(escaneados.isEmpty());
    assertEquals(List.of(CLAVE), almacen.borrados);
  }

  @Test
  void claveQueNoEsSubBarraUuidSeIgnora() {
    pendiente();
    for (String clave : new String[] {null, "", FILE_ID, SUB + "/", "/" + FILE_ID, SUB + "/" + FILE_ID + "/x",
        SUB + "/no-es-uuid", SUB + "/1-1-1-1-1", "snapstart/priming", SUB + "/" + FILE_ID.toUpperCase()}) {
      assertEquals("IGNORADO", servicio.procesar(clave), "clave: " + clave);
    }
    assertTrue(escaneados.isEmpty());
  }

  private void pendiente() {
    repo.crear(new ArchivoGuardado(SUB, FILE_ID, "laboratorio", "examen.pdf", "application/pdf", 77, CLAVE,
        "PENDING", 0));
  }
}
