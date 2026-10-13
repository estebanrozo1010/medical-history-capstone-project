import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  { path: 'registro', loadComponent: () => import('./features/auth/registro').then((m) => m.Registro) },
  { path: 'confirmar', loadComponent: () => import('./features/auth/confirmar').then((m) => m.Confirmar) },
  { path: 'perfil', canActivate: [authGuard], loadComponent: () => import('./features/perfil').then((m) => m.Perfil) },
  { path: 'carpetas', canActivate: [authGuard], loadComponent: () => import('./features/folders/carpetas').then((m) => m.Carpetas) },
  { path: 'carpetas/:nombre', canActivate: [authGuard], loadComponent: () => import('./features/folders/carpeta').then((m) => m.Carpeta) },
  { path: 'subir', canActivate: [authGuard], loadComponent: () => import('./features/upload/subir').then((m) => m.Subir) },
  { path: 'links', canActivate: [authGuard], loadComponent: () => import('./features/share/links').then((m) => m.Links) },
  // Pública: sin guard (quien recibe el link no tiene cuenta)
  { path: 'compartido/:token', loadComponent: () => import('./features/share/public-view/compartido').then((m) => m.Compartido) },
  { path: '**', redirectTo: 'perfil' },
];
