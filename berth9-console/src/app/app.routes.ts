import { Routes } from '@angular/router';
import { PipelinePage } from './pages/pipeline';
import { FilesPage } from './pages/files';
import { FileDetailPage } from './pages/file-detail';
import { ExceptionsPage } from './pages/exceptions';
import { MappingPage } from './pages/mapping';
import { PartnersPage } from './pages/partners';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'pipeline' },
  { path: 'pipeline', component: PipelinePage, title: 'Berth 9 · Live pipeline' },
  { path: 'files', component: FilesPage, title: 'Berth 9 · Files' },
  { path: 'files/:id', component: FileDetailPage, title: 'Berth 9 · File' },
  { path: 'exceptions', component: ExceptionsPage, title: 'Berth 9 · Exceptions' },
  { path: 'mapping', component: MappingPage, title: 'Berth 9 · Mapping studio' },
  { path: 'partners', component: PartnersPage, title: 'Berth 9 · Partners' },
  { path: '**', redirectTo: 'pipeline' },
];
