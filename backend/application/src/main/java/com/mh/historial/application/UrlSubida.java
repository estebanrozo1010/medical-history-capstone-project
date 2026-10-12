package com.mh.historial.application;

import java.util.Map;

/** URL firmada y las cabeceras que el cliente debe enviar tal cual en el PUT. */
public record UrlSubida(String url, Map<String, String> headers) {}
