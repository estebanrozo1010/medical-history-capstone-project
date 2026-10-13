package com.mh.historial.application;

/** Una parte subida: el número y el ETag que S3 devolvió al navegador. */
public record Parte(int partNumber, String etag) {}
