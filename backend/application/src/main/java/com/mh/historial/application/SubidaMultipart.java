package com.mh.historial.application;

/** Respuesta de POST /files/multipart/start (docs/04). */
public record SubidaMultipart(String fileId, String uploadId, long partSize) {}
