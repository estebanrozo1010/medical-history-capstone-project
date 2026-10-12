import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  { path: 'registro', loadComponent: () => import('./features/auth/registro').then((m) => m.Registro) },
  { path: 'confirmar', loadComponent: () => import('./features/auth/confirmar').then((m) => m.Confirmar) },
  { path: 'perfil', canActivate: [authGuard], loadComponent: () => import('./features/perfil').then((m) => m.Perfil) },
  { path: '**', redirectTo: 'perfil' },
];
