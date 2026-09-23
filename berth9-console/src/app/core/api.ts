import { Injectable, signal } from '@angular/core';

export type Mode = 'connecting' | 'live' | 'replay';

/** Recorded run of the real backend, bundled for the hosted demo (captured from a real local run). */
export interface ReplayData {
  recordedAt: string;
  overview: any;
  partners: any[];
  partnerDetails: Record<string, any>;
  jobs: any[];
  jobDetails: Record<string, any>;
  exceptions: any[];
  recordDetails: Record<string, any>;
  schemas: any;
  chaos: any;
  events: any[];
  mapping?: any;
}

export class ReplayOnlyError extends Error {
  constructor() {
    super('This is a recorded demo. Run Berth 9 locally to change data (see README).');
  }
}

/**
 * Talks to the Berth 9 API. Tries a live backend first (same origin, ?api=, or the last one used); if none
 * answers, falls back to the recorded replay bundled with the console so the demo always works.
 */
@Injectable({ providedIn: 'root' })
export class Api {
  readonly mode = signal<Mode>('connecting');
  readonly base = signal('');
  private replay: ReplayData | null = null;
  readonly ready: Promise<void>;

  constructor() {
    this.ready = this.connect();
  }

  get replayData(): ReplayData | null {
    return this.replay;
  }

  private async connect(): Promise<void> {
    const params = new URLSearchParams(location.search);
    const fromQuery = params.get('api');
    let stored: string | null = null;
    try { stored = localStorage.getItem('berth9-api'); } catch { /* storage unavailable */ }
    const candidates = [fromQuery, stored, ''].filter((c): c is string => c !== null && c !== undefined);
    if (params.get('mode') !== 'replay') {
      for (const candidate of [...new Set(candidates)]) {
        if (await this.probe(candidate)) {
          this.base.set(candidate.replace(/\/+$/, ''));
          this.mode.set('live');
          if (fromQuery) {
            try { localStorage.setItem('berth9-api', candidate); } catch { /* ignore */ }
          }
          return;
        }
      }
    }
    const res = await fetch('replay/replay.json');
    this.replay = await res.json();
    this.mode.set('replay');
  }

  private async probe(base: string): Promise<boolean> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 2500);
    try {
      const res = await fetch(base.replace(/\/+$/, '') + '/api/overview', { signal: controller.signal });
      return res.ok && (res.headers.get('content-type') ?? '').includes('json');
    } catch {
      return false;
    } finally {
      clearTimeout(timer);
    }
  }

  async get<T = any>(path: string): Promise<T> {
    await this.ready;
    if (this.mode() === 'replay') {
      return this.fromReplay(path) as T;
    }
    const res = await fetch(this.base() + path);
    return this.handle<T>(res);
  }

  async send<T = any>(method: 'POST' | 'PUT', path: string, body?: unknown, form?: FormData): Promise<T> {
    await this.ready;
    if (this.mode() === 'replay') {
      throw new ReplayOnlyError();
    }
    const headers: Record<string, string> = { 'X-Operator-Name': 'console' };
    let token: string | null = null;
    try { token = localStorage.getItem('berth9-operator-token'); } catch { /* ignore */ }
    if (token) headers['X-Operator-Token'] = token;
    if (!form && body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(this.base() + path, {
      method,
      headers,
      body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
    });
    return this.handle<T>(res);
  }

  eventsUrl(): string {
    return this.base() + '/api/events';
  }

  private async handle<T>(res: Response): Promise<T> {
    const type = res.headers.get('content-type') ?? '';
    const payload = type.includes('json') ? await res.json() : await res.text();
    if (!res.ok) {
      throw new Error(typeof payload === 'object' && payload?.error ? payload.error : `HTTP ${res.status}`);
    }
    return payload as T;
  }

  private fromReplay(path: string): unknown {
    const r = this.replay!;
    const clean = path.split('?')[0];
    const query = new URLSearchParams(path.split('?')[1] ?? '');
    let m: RegExpMatchArray | null;
    if (clean === '/api/overview') return r.overview;
    if (clean === '/api/partners') return r.partners;
    if ((m = clean.match(/^\/api\/partners\/([^/]+)$/))) return r.partnerDetails[m[1]];
    if (clean === '/api/jobs') {
      const partner = query.get('partner');
      return partner ? r.jobs.filter(j => j.partnerId === partner) : r.jobs;
    }
    if ((m = clean.match(/^\/api\/jobs\/(\d+)$/))) return r.jobDetails[m[1]];
    if ((m = clean.match(/^\/api\/jobs\/(\d+)\/ack$/))) return r.jobDetails[m[1]]?.ack ?? '';
    if (clean === '/api/exceptions') return r.exceptions;
    if ((m = clean.match(/^\/api\/records\/(\d+)$/))) return r.recordDetails[m[1]];
    if (clean === '/api/schemas') return r.schemas;
    if (clean === '/api/chaos') return r.chaos;
    if (clean === '/api/events/recent') return r.events;
    throw new Error('not available in the recorded demo: ' + clean);
  }
}
