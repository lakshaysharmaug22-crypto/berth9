import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { ago, label, statusTone } from '../core/format';

@Component({
  selector: 'b9-exceptions',
  imports: [RouterLink],
  templateUrl: './exceptions.html',
  styleUrl: './exceptions.css',
})
export class ExceptionsPage implements OnInit {
  protected readonly api = inject(Api);
  protected readonly items = signal<any[]>([]);
  protected readonly selected = signal<any>(null);
  protected readonly edits = signal<Record<string, string | undefined>>({});
  protected readonly audit = signal<any[]>([]);
  protected readonly toast = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly ago = ago;
  protected readonly tone = statusTone;
  protected readonly label = label;
  protected readonly Object = Object;

  protected readonly byRule = computed(() => {
    const counts: Record<string, number> = {};
    for (const r of this.items()) for (const v of r.violations ?? []) if (v.severity === 'ERROR') counts[v.rule] = (counts[v.rule] ?? 0) + 1;
    return Object.entries(counts).sort((a, b) => b[1] - a[1]);
  });

  protected readonly editable = computed(() => {
    const r = this.selected();
    if (!r) return [];
    const flagged = new Set((r.violations ?? []).map((v: any) => v.field).filter(Boolean));
    return Object.keys(r.values ?? {}).map(name => ({ name, flagged: flagged.has(name), value: r.values[name] }))
      .sort((a, b) => Number(b.flagged) - Number(a.flagged));
  });

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async open(r: any): Promise<void> {
    this.selected.set(r);
    this.edits.set({});
    try {
      const detail = await this.api.get(`/api/records/${r.id}`);
      this.audit.set(detail?.audit ?? []);
    } catch {
      this.audit.set([]);
    }
  }

  protected edit(field: string, value: string): void {
    this.edits.update(e => ({ ...e, [field]: value }));
  }

  protected async save(): Promise<void> {
    const r = this.selected();
    if (!r || !Object.keys(this.edits()).length) return;
    await this.act(async () => {
      const updated = await this.api.send('PUT', `/api/records/${r.id}`, this.edits());
      this.say(updated.status === 'ERROR' ? 'Still failing: ' + updated.violations.map((v: any) => v.rule).join(', ')
        : `Fixed → ${updated.status.toLowerCase()}; queued for delivery`);
      await this.load(updated.status === 'ERROR' ? updated : null);
    });
  }

  protected async dismiss(): Promise<void> {
    const r = this.selected();
    await this.act(async () => {
      await this.api.send('POST', `/api/records/${r.id}/dismiss?reason=duplicate%20or%20not%20payable`);
      this.say('Record dismissed');
      await this.load();
    });
  }

  protected async replay(): Promise<void> {
    const r = this.selected();
    await this.act(async () => {
      await this.api.send('POST', `/api/records/${r.id}/replay`);
      this.say('Sent back to the outbox');
      await this.load();
    });
  }

  private async load(keep: any = null): Promise<void> {
    const items = await this.api.get<any[]>('/api/exceptions?limit=300');
    this.items.set(items);
    const next = keep ? items.find(i => i.id === keep.id) : items[0];
    if (next) await this.open(next); else this.selected.set(null);
  }

  private async act(fn: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    try { await fn(); } catch (e: any) { this.say(e.message); } finally { this.busy.set(false); }
  }

  private say(m: string): void {
    this.toast.set(m);
    setTimeout(() => this.toast.set(null), 4000);
  }
}
