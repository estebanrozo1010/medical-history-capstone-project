package com.mh.historial.application;

/** Puerto de cifrado de campos sensibles; el sub es el encryption context. */
public interface Cifrador {

  byte[] cifrar(String texto, String sub);

  String descifrar(byte[] cifrado, String sub);
}
