
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { environment } from '../../../environments/environment';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  // Envoyer le JWT uniquement aux requêtes vers l'API métier.
  if (!req.url.startsWith(environment.apiUrl)) {
    return next(req);
  }

  const token = auth.getToken();

  const request = token
    ? req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      })
    : req;

  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401 && token) {
          auth.logout();
        } else if (error.status === 403) {
          auth.notifyAccessDenied();
        }
      }

      return throwError(() => error);
    })
  );
};
