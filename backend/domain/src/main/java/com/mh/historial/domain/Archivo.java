package com.mh.historial.domain;

/** Archivo que ve su dueño; status es el efectivo (un PENDING de más de 1 h se ve como UPLOAD_EXPIRED). */
public record Archivo(String fileId, String carpeta, String nombre, String contentType, long size, String status,
    long createdAt) {}
