package com.mh.historial.application;

/** Fila de mh-profiles: cédula y fecha de nacimiento cifradas con el sub como encryption context. */
public record PerfilGuardado(String sub, String email, String nombre, byte[] cedulaEnc,
    byte[] fechaNacimientoEnc, String genero, long createdAt, long updatedAt) {}
