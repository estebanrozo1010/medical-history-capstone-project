import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { Config } from '../config';
import { AuthService } from './auth.service';

/** Añade el ID token solo a las llamadas a apiUrl; ante 401 renueva y reintenta una vez. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiUrl } = inject(Config).value;
  if (!apiUrl || !req.url.startsWith(apiUrl)) return next(req);

  const auth = inject(AuthService);
  const router = inject(Router);
  const conToken = (r: HttpRequest<unknown>, token: string | null) =>
    token ? r.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : r;

  return from(auth.idToken()).pipe(
    switchMap((token) => next(conToken(req, token))),
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401) return throwError(() => err);
      return from(auth.refresh()).pipe(
        switchMap((token) => next(conToken(req, token))),
        catchError((err2: unknown) => {
          if (err2 instanceof HttpErrorResponse && err2.status === 401) {
            auth.logout();
            router.navigateByUrl('/login');
          }
          return throwError(() => err2);
        }),
      );
    }),
  );
};
