package com.mh.historial.application;

/**
 * Puerto del escaneo de un archivo subido (docs/02, docs/07). Devuelve el status final para mh-files.
 *
 * <p>PoC: {@link ValidadorDeTipo} (primeros bytes contra el tipo declarado), CLEAN o REJECTED. Versión objetivo: un
 * adaptador que lee el resultado de GuardDuty Malware Protection (NO_THREATS_FOUND → CLEAN, THREATS_FOUND →
 * INFECTED, otro → SCAN_FAILED). Cambiar de adaptador no toca {@link PostScanServicio}.
 */
public interface ArchivoScanner {

  String escanear(ArchivoGuardado archivo);
}
