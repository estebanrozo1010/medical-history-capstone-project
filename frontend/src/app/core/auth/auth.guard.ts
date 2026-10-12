import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = async () => {
  const router = inject(Router);
  return (await inject(AuthService).idToken()) ? true : router.createUrlTree(['/login']);
};
