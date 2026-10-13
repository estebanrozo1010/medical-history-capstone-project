package com.mh.historial.application;

import java.util.List;

/** Link que ve su dueño en GET /links; archivos trae solo los que aún existen. */
public record LinkDelUsuario(LinkGuardado link, List<ArchivoGuardado> archivos) {}
