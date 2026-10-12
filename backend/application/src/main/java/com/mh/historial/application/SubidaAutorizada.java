package com.mh.historial.application;

import java.util.Map;

/** Respuesta de POST /files/upload-url (docs/04). */
public record SubidaAutorizada(String fileId, String url, Map<String, String> headers, long expiresIn) {}
