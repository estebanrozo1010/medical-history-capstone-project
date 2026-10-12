const MENSAJES: Record<string, string> = {
  UsernameExistsException: 'Ya existe una cuenta con ese correo.',
  InvalidPasswordException: 'La contraseña debe tener al menos 8 caracteres, con mayúscula, minúscula y número.',
  InvalidParameterException: 'Revisa los datos ingresados.',
  CodeMismatchException: 'El código no es correcto.',
  ExpiredCodeException: 'El código venció. Solicita uno nuevo.',
  NotAuthorizedException: 'Correo o contraseña incorrectos.',
  UserNotConfirmedException: 'Tu cuenta aún no está confirmada.',
  LimitExceededException: 'Demasiados intentos. Espera unos minutos.',
  TooManyRequestsException: 'Demasiados intentos. Espera unos minutos.',
};

export const mensajeError = (err: unknown): string =>
  MENSAJES[(err as { code?: string })?.code ?? ''] ?? 'Ocurrió un error. Inténtalo de nuevo.';
