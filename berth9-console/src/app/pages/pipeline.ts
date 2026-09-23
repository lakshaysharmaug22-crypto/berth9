import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { Live } from '../core/live';
import { ago, describeEvent, label, num, statusTone } from '../core/format';
import { PipelineDiagram } from '../ui/pipeline-diagram';

@Component({
  selector: 'b9-pipeline',
  imports: [PipelineDiagram, RouterLink],
  templateUrl: './pipeline.html',
  styleUrl: './pipeline.css',
})
export class PipelinePage implements OnInit {
  protected readonly api = inject(Api);
  protected readonly live = inject(Live);
  protected readonly overview = signal<any>(null);
  protected readonly partners = signal<any[]>([]);
  protected readonly chaos = signal<any>({ erpDown: false, latencyMs: 0, failRatePct: 0 });
  protected readonly toast = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly ago = ago;
  protected readonly num = num;
  protected readonly tone = statusTone;
  protected readonly label = label;
  protected readonly describe = describeEvent;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly kpis = computed(() => {
    const c = this.live.counters();
    const o = this.overview();
    if (this.api.mode() === 'live' && o) {
      const r = o.records ?? {};
      return [
        { label: 'Files received', value: o.totals.files, sub: `${num(o.totals.avgFileMs)} ms avg per file` },
        { label: 'Records processed', value: o.totals.records, sub: `${num(o.totals.valid)} valid · ${num(o.totals.warnings)} warnings` },
        { label: 'Delivered to ERP', value: r.DELIVERED ?? 0, sub: 'outbox, idempotent' },
        { label: 'Open exceptions', value: (r.ERROR ?? 0) + (r.DEAD_LETTER ?? 0), sub: 'waiting for review', tone: 'err' },
      ];
    }
    return [
      { label: 'Files received', value: c.files, sub: `${c.duplicates} duplicates skipped` },
      { label: 'Records processed', value: c.records, sub: `${c.valid} valid · ${c.warnings} warnings` },
      { label: 'Delivered to ERP', value: c.delivered, sub: `${c.acks} EDI 997 acks sent` },
      { label: 'Open exceptions', value: c.errors, sub: 'waiting for review', tone: 'err' },
    ];
  });

  constructor() {
    effect(() => {
      const e = this.live.last();
      if (e && this.api.mode() === 'live' && ['file.processed', 'delivery.succeeded', 'delivery.failed', 'record.fixed'].includes(e.type)) {
        this.scheduleRefresh();
      }
    });
  }

  async ngOnInit(): Promise<void> {
    this.partners.set(await this.api.get('/api/partners'));
    await this.refresh();
    try { this.chaos.set(await this.api.get('/api/chaos')); } catch { /* optional */ }
  }

  protected async runDemo(): Promise<void> {
    await this.act(() => this.api.send('POST', '/api/demo/run?gapMillis=1800'), 'Dropping 7 partner files into the inbox…');
  }

  protected async setChaos(patch: Record<string, unknown>): Promise<void> {
    await this.act(async () => this.chaos.set(await this.api.send('POST', '/api/chaos', { ...this.chaos(), ...patch })));
  }

  private async act(fn: () => Promise<unknown>, message?: string): Promise<void> {
    this.busy.set(true);
    try {
      await fn();
      if (message) this.say(message);
    } catch (e: any) {
      this.say(e.message);
    } finally {
      this.busy.set(false);
    }
  }

  private say(message: string): void {
    this.toast.set(message);
    setTimeout(() => this.toast.set(null), 3500);
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) return;
    this.refreshTimer = setTimeout(async () => {
      this.refreshTimer = null;
      await this.refresh();
    }, 600);
  }

  private async refresh(): Promise<void> {
    this.overview.set(await this.api.get('/api/overview'));
  }
}
