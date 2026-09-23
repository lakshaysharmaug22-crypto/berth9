import { Injectable, NgZone, computed, inject, signal } from '@angular/core';
import { Api } from './api';

export interface PipelineEvent {
  seq: number;
  type: string;
  at: string;
  data: any;
}

/**
 * The live event stream: Server-Sent Events from the backend, or the recorded timeline played back in a
 * loop in replay mode. Pages and the pipeline diagram read the same signals either way.
 */
@Injectable({ providedIn: 'root' })
export class Live {
  private readonly api = inject(Api);
  private readonly zone = inject(NgZone);

  readonly events = signal<PipelineEvent[]>([]);
  readonly last = signal<PipelineEvent | null>(null);
  readonly connected = signal(false);
  readonly breaker = signal<string>('CLOSED');
  readonly counters = signal({ files: 0, records: 0, valid: 0, warnings: 0, errors: 0, delivered: 0, acks: 0, duplicates: 0 });
  readonly recent = computed(() => this.events().slice(-60).reverse());
  private started = false;
  private replayTimer: ReturnType<typeof setTimeout> | null = null;

  async start(): Promise<void> {
    if (this.started) return;
    this.started = true;
    await this.api.ready;
    if (this.api.mode() === 'live') {
      const history = await this.api.get<PipelineEvent[]>('/api/events/recent');
      history.forEach(e => this.push(e, false));
      this.openStream();
    } else {
      this.playReplay();
    }
  }

  private openStream(): void {
    const source = new EventSource(this.api.eventsUrl());
    source.addEventListener('pipeline', (msg: MessageEvent) => {
      this.zone.run(() => this.push(JSON.parse(msg.data), true));
    });
    source.onopen = () => this.zone.run(() => this.connected.set(true));
    source.onerror = () => this.zone.run(() => this.connected.set(false));
  }

  /** Plays the recorded timeline with gaps compressed to at most 1.4 s, then loops. */
  private playReplay(): void {
    const timeline = this.api.replayData?.events ?? [];
    if (!timeline.length) return;
    this.connected.set(true);
    let i = 0;
    const step = () => {
      if (i >= timeline.length) {
        i = 0;
        this.reset();
        this.replayTimer = setTimeout(step, 2500);
        return;
      }
      const e = timeline[i++];
      this.push({ ...e, at: new Date().toISOString() }, true);
      const next = timeline[i];
      const gap = next ? Math.min(1400, Math.max(120, Date.parse(next.at) - Date.parse(e.at))) : 1200;
      this.replayTimer = setTimeout(step, gap);
    };
    step();
  }

  private reset(): void {
    this.events.set([]);
    this.breaker.set('CLOSED');
    this.counters.set({ files: 0, records: 0, valid: 0, warnings: 0, errors: 0, delivered: 0, acks: 0, duplicates: 0 });
  }

  private push(e: PipelineEvent, animate: boolean): void {
    this.events.update(list => [...list.slice(-400), e]);
    if (animate) this.last.set(e);
    const c = { ...this.counters() };
    switch (e.type) {
      case 'file.processed':
        c.files++;
        c.records += e.data.total ?? 0;
        c.valid += e.data.valid ?? 0;
        c.warnings += e.data.warnings ?? 0;
        c.errors += e.data.errors ?? 0;
        break;
      case 'delivery.succeeded':
        c.delivered += e.data.records ?? 0;
        break;
      case 'ack.sent':
        c.acks++;
        break;
      case 'file.duplicate':
        c.duplicates++;
        break;
      case 'breaker.state':
        this.breaker.set(e.data.state);
        break;
    }
    this.counters.set(c);
  }
}
