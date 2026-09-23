import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './core/api';
import { Live } from './core/live';

interface NavItem { path: string; label: string; icon: string; }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly api = inject(Api);
  protected readonly live = inject(Live);
  protected readonly theme = signal<'system' | 'light' | 'dark'>(this.readTheme());
  protected readonly nav: NavItem[] = [
    { path: '/pipeline', label: 'Live pipeline', icon: 'M3 12h4l3-8 4 16 3-8h4' },
    { path: '/files', label: 'Files', icon: 'M6 3h9l5 5v13H6zM14 3v6h6' },
    { path: '/exceptions', label: 'Exceptions', icon: 'M12 3l10 18H2zM12 10v5M12 18h.01' },
    { path: '/mapping', label: 'Mapping studio', icon: 'M4 6h6M4 12h6M4 18h6M14 6h6M14 12h6M14 18h6M10 6l4 6M10 12l4 6' },
    { path: '/partners', label: 'Partners', icon: 'M3 21V9l9-6 9 6v12M9 21v-7h6v7' },
  ];
  protected readonly modeLabel = computed(() => {
    switch (this.api.mode()) {
      case 'live': return 'Live backend';
      case 'replay': return 'Recorded demo';
      default: return 'Connecting…';
    }
  });

  constructor() {
    this.live.start();
  }

  protected cycleTheme(): void {
    const next = this.theme() === 'system' ? 'dark' : this.theme() === 'dark' ? 'light' : 'system';
    this.theme.set(next);
    if (next === 'system') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', next);
    }
    try { localStorage.setItem('berth9-theme', next); } catch { /* ignore */ }
  }

  private readTheme(): 'system' | 'light' | 'dark' {
    try {
      const t = localStorage.getItem('berth9-theme');
      return t === 'light' || t === 'dark' ? t : 'system';
    } catch {
      return 'system';
    }
  }
}
