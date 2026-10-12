package com.mh.historial.adapter.out.kms;

import com.mh.historial.application.Cifrador;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;

/** Encrypt/Decrypt directo (valores cortos) con el sub como encryption context: un cifrado no se descifra con otro sub. */
public class KmsCifrador implements Cifrador {

  private final KmsClient kms;
  private final String keyArn;

  public KmsCifrador(KmsClient kms, String keyArn) {
    this.kms = kms;
    this.keyArn = keyArn;
  }

  @Override
  public byte[] cifrar(String texto, String sub) {
    return kms.encrypt(r -> r.keyId(keyArn).encryptionContext(Map.of("sub", sub))
        .plaintext(SdkBytes.fromString(texto, StandardCharsets.UTF_8))).ciphertextBlob().asByteArray();
  }

  @Override
  public String descifrar(byte[] cifrado, String sub) {
    return kms.decrypt(r -> r.keyId(keyArn).encryptionContext(Map.of("sub", sub))
        .ciphertextBlob(SdkBytes.fromByteArray(cifrado))).plaintext().asUtf8String();
  }
}
