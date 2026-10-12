package com.mh.historial.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mh.historial.domain.DatosPerfil;
import com.mh.historial.domain.ErrorDeDominio;
import com.mh.historial.domain.Perfil;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PerfilServicioTest {

  static final DatosPerfil DATOS = DatosPerfil.de("Ana Pérez", "1020304050", "1990-05-17", "F");
  static final DatosPerfil OTROS = DatosPerfil.de("Ana María Pérez", "99887766", "1991-01-02", "O");

  final RepoEnMemoria repo = new RepoEnMemoria();
  final MutableClock reloj = new MutableClock(1_700_000_000L);
  final PerfilServicio servicio = new PerfilServicio(repo, new CifradorFalso(), reloj);

  @Test
  void registrarGuardaCedulaYFechaCifradasConElSub() {
    Perfil p = servicio.registrar("sub-a", "ana@x.com", DATOS);

    assertEquals(new Perfil("ana@x.com", DATOS), p);
    PerfilGuardado g = repo.datos.get("sub-a");
    assertEquals("sub-a", g.sub());
    assertEquals("Ana Pérez", g.nombre());
    assertEquals("F", g.genero());
    assertArrayEquals(CifradorFalso.cifrado("1020304050", "sub-a"), g.cedulaEnc());
    assertArrayEquals(CifradorFalso.cifrado("1990-05-17", "sub-a"), g.fechaNacimientoEnc());
    assertEquals(1_700_000_000L, g.createdAt());
    assertEquals(1_700_000_000L, g.updatedAt());
  }

  @Test
  void segundoRegistroDaConflictoYNoPisaElPrimero() {
    servicio.registrar("sub-a", "ana@x.com", DATOS);

    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.registrar("sub-a", "ana@x.com", OTROS));
    assertEquals(ErrorDeDominio.Tipo.CONFLICTO, e.tipo());
    assertEquals(DATOS, servicio.obtener("sub-a").datos());
  }

  @Test
  void obtenerDescifraLoGuardado() {
    servicio.registrar("sub-a", "ana@x.com", DATOS);

    assertEquals(new Perfil("ana@x.com", DATOS), servicio.obtener("sub-a"));
  }

  @Test
  void obtenerSinPerfilDaNoEncontrado() {
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.obtener("sub-a"));
    assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo());
  }

  @Test
  void cadaUsuarioSoloVeSuPerfil() {
    servicio.registrar("sub-a", "ana@x.com", DATOS);
    servicio.registrar("sub-b", "beto@x.com", OTROS);

    assertEquals(new Perfil("ana@x.com", DATOS), servicio.obtener("sub-a"));
    assertEquals(new Perfil("beto@x.com", OTROS), servicio.obtener("sub-b"));
    assertThrows(ErrorDeDominio.class, () -> servicio.obtener("sub-c"));
  }

  @Test
  void actualizarCambiaDatosYConservaCreatedAt() {
    servicio.registrar("sub-a", "ana@x.com", DATOS);
    reloj.segundos = 1_700_000_500L;

    Perfil p = servicio.actualizar("sub-a", "ana.nuevo@x.com", OTROS);

    assertEquals(new Perfil("ana.nuevo@x.com", OTROS), p);
    assertEquals(p, servicio.obtener("sub-a"));
    assertEquals(1_700_000_000L, repo.datos.get("sub-a").createdAt());
    assertEquals(1_700_000_500L, repo.datos.get("sub-a").updatedAt());
  }

  @Test
  void actualizarSinPerfilDaNoEncontrado() {
    ErrorDeDominio e = assertThrows(ErrorDeDominio.class, () -> servicio.actualizar("sub-a", "ana@x.com", DATOS));
    assertEquals(ErrorDeDominio.Tipo.NO_ENCONTRADO, e.tipo());
    assertFalse(repo.datos.containsKey("sub-a"));
  }

  /** Imita KMS: el cifrado queda atado al sub (encryption context) y no se descifra con otro. */
  static class CifradorFalso implements Cifrador {
    static byte[] cifrado(String texto, String sub) {
      return ("enc[" + sub + "]" + new StringBuilder(texto).reverse()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] cifrar(String texto, String sub) {
      return cifrado(texto, sub);
    }

    @Override
    public String descifrar(byte[] cifrado, String sub) {
      String s = new String(cifrado, StandardCharsets.UTF_8);
      String prefijo = "enc[" + sub + "]";
      if (!s.startsWith(prefijo)) {
        throw new IllegalStateException("encryption context distinto");
      }
      return new StringBuilder(s.substring(prefijo.length())).reverse().toString();
    }
  }

  /** Imita las escrituras condicionales de DynamoDB. */
  static class RepoEnMemoria implements PerfilRepositorio {
    final Map<String, PerfilGuardado> datos = new HashMap<>();

    @Override
    public Optional<PerfilGuardado> buscar(String sub) {
      return Optional.ofNullable(datos.get(sub));
    }

    @Override
    public void crear(PerfilGuardado p) {
      if (datos.putIfAbsent(p.sub(), p) != null) {
        throw new ErrorDeDominio(ErrorDeDominio.Tipo.CONFLICTO, "ya existe");
      }
    }

    @Override
    public void actualizar(PerfilGuardado p) {
      PerfilGuardado actual = datos.get(p.sub());
      if (actual == null) {
        throw new ErrorDeDominio(ErrorDeDominio.Tipo.NO_ENCONTRADO, "no existe");
      }
      datos.put(p.sub(), new PerfilGuardado(p.sub(), p.email(), p.nombre(), p.cedulaEnc(),
          p.fechaNacimientoEnc(), p.genero(), actual.createdAt(), p.updatedAt()));
    }
  }

  static class MutableClock extends Clock {
    long segundos;

    MutableClock(long segundos) {
      this.segundos = segundos;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochSecond(segundos);
    }
  }
}
