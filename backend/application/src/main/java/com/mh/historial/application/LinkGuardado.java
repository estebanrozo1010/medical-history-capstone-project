package com.mh.historial.application;

import java.util.List;

/**
 * Fila de mh-links (docs/04). expiresAt es el atributo TTL, pero siempre se valida en código (el TTL borra con retraso).
 * permiso: ver, descargar o ambos (lo que quien recibe el link puede hacer con cada archivo).
 */
public record LinkGuardado(String token, String userSub, List<String> fileIds, boolean activo, long expiresAt,
    long createdAt, String permiso) {}
