package com.mh.historial.domain;

/** Perfil descifrado que ve su dueño; el email viene del ID token. */
public record Perfil(String email, DatosPerfil datos) {}
