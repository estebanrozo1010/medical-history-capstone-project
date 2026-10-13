package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mh.historial.application.ArchivoServicioTest.AlmacenFalso;
import com.mh.historial.application.ArchivoServicioTest.RepoEnMemoria;
import com.mh.historial.application.PerfilServicioTest.MutableClock;
import com.mh.historial.domain.ErrorDeDominio;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LinkServicioTest {

  static final long AHORA = 1_700_000_000L;

  final RepoEnMemoria archivos = new RepoEnMemoria();
  final LinksEnMemoria links = new LinksEnMemoria();
  final AlmacenFalso almacen = new AlmacenFalso();
  final MutableClock reloj = new MutableClock(AHORA);
  final LinkServicio servicio = new LinkServicio(links, archivos, almacen, reloj);

  // --- CrearLink ---

  @Test
  void crearGuardaActivoConExpiresAtYCreatedAtDelReloj() {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "f2", "CLEAN");

    LinkGuardado l = servicio.crear("sub-a", List.of("f1", "f2"), 30, "ambos");

    assertEquals(new LinkGuardado(l.token(), "sub-a", List.of("f1", "f2"), true, AHORA + 1800, AHORA, "ambos"), l);
    assertEquals(l, links.datos.get(l.token()));
  }

  @Test
  void elTokenSon32BytesEnBase64urlYCambiaEnCadaLink() {
    archivo("sub-a", "f1", "CLEAN");

    String t1 = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();
    String t2 = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();

    for (String t : List.of(t1, t2)) {
      assertEquals(43, t.length());
      assertTrue(t.matches("[A-Za-z0-9_-]+"), t);
      assertEquals(32, Base64.getUrlDecoder().decode(t).length);
    }
    assertNotEquals(t1, t2);
  }

  @Test
  void aceptaLosLimites50ArchivosY1440Minutos() {
    List<String> ids = IntStream.range(0, 50).mapToObj(i -> "f" + i).toList();
    ids.forEach(id -> archivo("sub-a", id, "CLEAN"));

    LinkGuardado l = servicio.crear("sub-a", ids, 1440, "ambos");

    assertEquals(50, l.fileIds().size());
    assertEquals(AHORA + 86_400, l.expiresAt());
  }

  @Test
  void validacionDeFileIdsYTtlSinGuardarNada() {
    archivo("sub-a", "f1", "CLEAN");
    List<String> cincuentaYUno = IntStream.range(0, 51).mapToObj(i -> "f" + i).toList();
    cincuentaYUno.forEach(id -> archivo("sub-a", id, "CLEAN"));
    List<Object[]> casos = List.of(
        new Object[] {null, 10},
        new Object[] {List.of(), 10},
        new Object[] {cincuentaYUno, 10},
        new Object[] {List.of("f1", "f1"), 10},
        new Object[] {List.of("f1"), 0},
        new Object[] {List.of("f1"), 1441},
        new Object[] {List.of("f1"), -5});

    for (Object[] c : casos) {
      @SuppressWarnings("unchecked")
      List<String> ids = (List<String>) c[0];
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.crear("sub-a", ids, (int) c[1], "ambos"));
      assertEquals(ErrorDeDominio.Tipo.VALIDACION, e.tipo(), ids + " / " + c[1]);
    }
    assertTrue(links.datos.isEmpty());
  }

  @Test
  void unArchivoAjenoOInexistenteResponde404AunqueOtroNoEsteClean() {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "f2", "PENDING");
    archivo("sub-b", "fb", "CLEAN");

    for (List<String> ids : List.of(List.of("f1", "fb"), List.of("f1", "no-existe"), List.of("f2", "fb"))) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.crear("sub-a", ids, 10, "ambos"));
      assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo(), ids.toString());
    }
    assertTrue(links.datos.isEmpty());
  }

  @Test
  void unArchivoNoCleanResponde409() {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "pend", "PENDING");
    archivo("sub-a", "rech", "REJECTED");

    for (String id : List.of("pend", "rech")) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class,
          () -> servicio.crear("sub-a", List.of("f1", id), 10, "ambos"));
      assertEquals(ErrorDeDominio.Tipo.CONFLICTO, e.tipo(), id);
    }
    assertTrue(links.datos.isEmpty());
  }

  // --- ListarLinks ---

  @Test
  void listarDevuelveSoloLosPropiosVigentesDelMasNuevoAlMasViejo() {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "f2", "CLEAN");
    archivo("sub-b", "fb", "CLEAN");
    String vencido = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();
    reloj.segundos += 10;
    String viejo = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();
    reloj.segundos += 10;
    String nuevo = servicio.crear("sub-a", List.of("f2", "f1"), 60, "ambos").token();
    servicio.crear("sub-b", List.of("fb"), 60, "ambos");
    reloj.segundos = AHORA + 60;

    List<LinkDelUsuario> lista = servicio.listar("sub-a");

    assertEquals(List.of(nuevo, viejo), lista.stream().map(l -> l.link().token()).toList());
    assertFalse(lista.stream().anyMatch(l -> l.link().token().equals(vencido)));
    assertEquals(List.of("f2", "f1"), lista.get(0).archivos().stream().map(ArchivoGuardado::fileId).toList());
    assertEquals("f1.pdf", lista.get(1).archivos().get(0).nombre());
  }

  @Test
  void unArchivoEliminadoDesapareceDelLinkPeroElLinkSigue() {
    archivo("sub-a", "f1", "CLEAN");
    archivo("sub-a", "f2", "CLEAN");
    servicio.crear("sub-a", List.of("f1", "f2"), 60, "ambos");
    archivos.datos.removeIf(a -> a.fileId().equals("f1"));

    List<LinkDelUsuario> lista = servicio.listar("sub-a");

    assertEquals(1, lista.size());
    assertEquals(List.of("f2"), lista.get(0).archivos().stream().map(ArchivoGuardado::fileId).toList());
  }

  @Test
  void listarSinLinksDevuelveVacio() {
    assertEquals(List.of(), servicio.listar("sub-a"));
  }

  // --- CambiarEstado ---

  @Test
  void elDuenoApagaYPrendeSuLink() {
    archivo("sub-a", "f1", "CLEAN");
    String token = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();

    LinkGuardado apagado = servicio.cambiarEstado("sub-a", token, false);

    assertFalse(apagado.activo());
    assertFalse(links.datos.get(token).activo());
    assertTrue(servicio.cambiarEstado("sub-a", token, true).activo());
    assertTrue(links.datos.get(token).activo());
  }

  @Test
  void linkAjenoInexistenteVencidoOMalFormadoResponde404SinCambiarNada() {
    archivo("sub-a", "f1", "CLEAN");
    String token = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();
    String inexistente = "A".repeat(43);

    for (String[] caso : new String[][] {{"sub-b", token}, {"sub-a", inexistente}, {"sub-a", "corto"},
        {"sub-a", token + "x"}, {"sub-a", null}}) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.cambiarEstado(caso[0], caso[1], false));
      assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo(), caso[0] + " " + caso[1]);
    }
    reloj.segundos = AHORA + 60;
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.cambiarEstado("sub-a", token, false));
    assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo());
    assertTrue(links.datos.get(token).activo());
  }

  @Test
  void siElTtlBorraElLinkAntesDeActualizarResponde404() {
    archivo("sub-a", "f1", "CLEAN");
    String token = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();
    links.borrarAlActualizar = true;

    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.cambiarEstado("sub-a", token, false));
    assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo());
  }

  // --- ResolverLink (pública) ---

  @Test
  void linkActivoYVigenteListaSoloLosCleanQueExistenEnElOrdenDelLink() {
    List.of("f1", "f2", "f3", "f4").forEach(id -> archivo("sub-a", id, "CLEAN"));
    archivo("sub-b", "f1", "CLEAN");
    LinkGuardado l = servicio.crear("sub-a", List.of("f4", "f2", "f3", "f1"), 60, "ambos");
    archivos.cambiarEstado("sub-a", "f2", "CLEAN", "REJECTED");
    archivos.eliminar("sub-a", "f3");

    LinkDelUsuario r = servicio.resolver(l.token()).orElseThrow();

    assertEquals(l, r.link());
    assertEquals(List.of("sub-a/f4", "sub-a/f1"), r.archivos().stream().map(ArchivoGuardado::s3Key).toList());
  }

  @Test
  void linkSinArchivosDisponiblesSigueVigenteConListaVacia() {
    archivo("sub-a", "f1", "CLEAN");
    String token = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();
    archivos.eliminar("sub-a", "f1");

    assertEquals(List.of(), servicio.resolver(token).orElseThrow().archivos());
  }

  @Test
  void linkApagadoVencidoOInexistenteNoSeResuelve() {
    archivo("sub-a", "f1", "CLEAN");
    String apagado = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();
    servicio.cambiarEstado("sub-a", apagado, false);
    String vence = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();

    reloj.segundos = AHORA + 59;
    assertTrue(servicio.resolver(vence).isPresent());
    reloj.segundos = AHORA + 60;
    for (String token : List.of(apagado, vence, "A".repeat(43))) {
      assertEquals(Optional.empty(), servicio.resolver(token), token);
    }
  }

  @Test
  void elVencimientoSeValidaEnCodigoAunqueElTtlNoHayaBorradoElLink() {
    archivo("sub-a", "f1", "CLEAN");
    String token = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();
    reloj.segundos = AHORA + 3600;

    assertTrue(links.datos.containsKey(token));
    assertEquals(Optional.empty(), servicio.resolver(token));
    assertEquals(Optional.empty(), servicio.urlDescarga(token, "f1", false));
  }

  @Test
  void tokenMalFormadoNoConsultaDynamo() {
    for (String token : new String[] {null, "", "corto", "A".repeat(44), "A".repeat(42) + "=", "A".repeat(42) + "/"}) {
      assertEquals(Optional.empty(), servicio.resolver(token), token);
      assertEquals(Optional.empty(), servicio.urlDescarga(token, "f1", false), token);
    }
    assertEquals(0, links.lecturas);
  }

  // --- DescargarDeLink (pública) ---

  @Test
  void descargaFirmaUnMinutoConElNombreOriginal() {
    archivos.crear(new ArchivoGuardado("sub-a", "f1", "cardiologia", "Exámen ñ.pdf", "application/pdf", 1, "sub-a/f1",
        "CLEAN", AHORA));
    String token = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();

    assertEquals(Optional.of("https://s3/get/sub-a/f1"), servicio.urlDescarga(token, "f1", false));
    assertEquals("sub-a/f1|" + ArchivoServicio.disposicion("Exámen ñ.pdf") + "|PT1M", almacen.descarga);
  }

  @Test
  void verFirmaInlineParaQueElNavegadorLoMuestreConElMismoNombre() {
    archivos.crear(new ArchivoGuardado("sub-a", "f1", "cardiologia", "Exámen ñ.pdf", "application/pdf", 1, "sub-a/f1",
        "CLEAN", AHORA));
    String token = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();

    assertEquals(Optional.of("https://s3/get/sub-a/f1"), servicio.urlDescarga(token, "f1", true));
    assertEquals("sub-a/f1|inline; filename=\"Examen n.pdf\"; filename*=UTF-8''Ex%C3%A1men%20%C3%B1.pdf|PT1M",
        almacen.descarga);
  }

  @Test
  void fileIdFueraDelLinkONoCleanOEliminadoResponde404SinFirmar() {
    List.of("f1", "f2", "f3", "otro").forEach(id -> archivo("sub-a", id, "CLEAN"));
    String token = servicio.crear("sub-a", List.of("f1", "f2", "f3"), 60, "ambos").token();
    archivos.cambiarEstado("sub-a", "f2", "CLEAN", "REJECTED");
    archivos.eliminar("sub-a", "f3");

    for (String fileId : List.of("otro", "no-existe", "f2", "f3")) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.urlDescarga(token, fileId, false));
      assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo(), fileId);
    }
    assertEquals(null, almacen.descarga);
  }

  @Test
  void crearGuardaElPermisoYRechazaUnoDesconocido() {
    archivo("sub-a", "f1", "CLEAN");

    for (String p : List.of("ver", "descargar", "ambos")) {
      assertEquals(p, servicio.crear("sub-a", List.of("f1"), 10, p).permiso());
    }
    for (String p : new String[] {null, "", "otro", "VER"}) {
      ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.crear("sub-a", List.of("f1"), 10, p));
      assertEquals(ErrorDeDominio.Tipo.VALIDACION, e.tipo(), String.valueOf(p));
    }
    assertEquals(3, links.datos.size());
  }

  @Test
  void elPermisoDelLinkMandaSobreElModoPedido() {
    archivo("sub-a", "f1", "CLEAN");
    String soloVer = servicio.crear("sub-a", List.of("f1"), 60, "ver").token();
    String soloDescargar = servicio.crear("sub-a", List.of("f1"), 60, "descargar").token();
    String ambos = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();

    // token, modo pedido (ver?), disposición firmada
    for (Object[] c : new Object[][] {{soloVer, false, "inline"}, {soloVer, true, "inline"},
        {soloDescargar, true, "attachment"}, {soloDescargar, false, "attachment"},
        {ambos, true, "inline"}, {ambos, false, "attachment"}}) {
      servicio.urlDescarga((String) c[0], "f1", (boolean) c[1]);
      assertTrue(almacen.descarga.startsWith("sub-a/f1|" + c[2] + ";"), almacen.descarga);
    }
  }

  @Test
  void descargaDeLinkApagadoVencidoOInexistenteNoFirma() {
    archivo("sub-a", "f1", "CLEAN");
    String apagado = servicio.crear("sub-a", List.of("f1"), 60, "ambos").token();
    servicio.cambiarEstado("sub-a", apagado, false);
    String vence = servicio.crear("sub-a", List.of("f1"), 1, "ambos").token();
    reloj.segundos = AHORA + 60;

    for (String token : List.of(apagado, vence, "A".repeat(43))) {
      assertEquals(Optional.empty(), servicio.urlDescarga(token, "f1", false), token);
    }
    assertEquals(null, almacen.descarga);
  }

  private void archivo(String sub, String fileId, String status) {
    archivos.crear(new ArchivoGuardado(sub, fileId, "otros", fileId + ".pdf", "application/pdf", 1, sub + "/" + fileId,
        status, AHORA));
  }

  static class LinksEnMemoria implements LinkRepositorio {
    final Map<String, LinkGuardado> datos = new LinkedHashMap<>();
    boolean borrarAlActualizar;
    int lecturas;

    @Override
    public void crear(LinkGuardado l) {
      datos.put(l.token(), l);
    }

    @Override
    public List<LinkGuardado> listar(String userSub) {
      // El GSI no garantiza un orden que el servicio deba suponer
      List<LinkGuardado> l = new ArrayList<>(datos.values().stream().filter(x -> x.userSub().equals(userSub)).toList());
      Collections.shuffle(l);
      return l;
    }

    @Override
    public Optional<LinkGuardado> obtener(String token) {
      lecturas++;
      return Optional.ofNullable(datos.get(token));
    }

    @Override
    public boolean cambiarActivo(String token, boolean activo) {
      if (borrarAlActualizar) {
        datos.remove(token);
      }
      LinkGuardado l = datos.get(token);
      if (l == null) {
        return false;
      }
      datos.put(token,
          new LinkGuardado(l.token(), l.userSub(), l.fileIds(), activo, l.expiresAt(), l.createdAt(), l.permiso()));
      return true;
    }
  }
}
