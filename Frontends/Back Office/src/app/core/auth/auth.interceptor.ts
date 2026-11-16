import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);

  // Ne pas ajouter le JWT à la requête de connexion IAM.
  if (!request.url.startsWith(environment.apiUrl)) {
    return next(request);
  }

  const token = auth.getToken();

  const outgoingRequest = token
    ? request.clone({
        setHeaders: { Authorization: `Bearer ${token}` }
      })
    : request;

  return next(outgoingRequest).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401 && token) {
          auth.expireSession();
        } else if (error.status === 403) {
          auth.notifyAccessDenied();
        }
      }

      return throwError(() => error);
    })
  );
};
