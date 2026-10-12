package com.mh.historial.application;

/** Fila de mh-files (docs/04). s3Key es {userSub}/{fileId}, sin carpeta ni nombre. */
public record ArchivoGuardado(String userSub, String fileId, String carpeta, String nombre, String contentType,
    long size, String s3Key, String status, long createdAt) {}
