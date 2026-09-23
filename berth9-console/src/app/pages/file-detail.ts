import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { ago, label, num, statusTone } from '../core/format';

@Component({
  selector: 'b9-file-detail',
  imports: [RouterLink],
  templateUrl: './file-detail.html',
  styleUrl: './file-detail.css',
})
export class FileDetailPage implements OnInit {
  readonly id = input.required<string>();
  private readonly api = inject(Api);
  protected readonly data = signal<any>(null);
  protected readonly ack = signal<string | null>(null);
  protected readonly selected = signal<any>(null);
  protected readonly status = signal<string>('');
  protected readonly ago = ago;
  protected readonly num = num;
  protected readonly tone = statusTone;
  protected readonly label = label;
  protected readonly Object = Object;

  protected readonly records = computed(() => {
    const all = this.data()?.records ?? [];
    return this.status() ? all.filter((r: any) => r.status === this.status()) : all;
  });

  protected readonly counts = computed(() => {
    const c: Record<string, number> = {};
    for (const r of this.data()?.records ?? []) c[r.status] = (c[r.status] ?? 0) + 1;
    return Object.entries(c);
  });

  async ngOnInit(): Promise<void> {
    const d = await this.api.get(`/api/jobs/${this.id()}`);
    this.data.set(d);
    this.selected.set(d.records.find((r: any) => r.violations?.length) ?? d.records[0] ?? null);
    if (d.job.hasAck) {
      try { this.ack.set(await this.api.get(`/api/jobs/${this.id()}/ack`)); } catch { /* optional */ }
    }
  }

  protected fields(record: any): { name: string; value: unknown; from: string }[] {
    return Object.entries(record.values ?? {}).map(([name, value]) => ({
      name, value, from: ((record.lineage ?? {})[name] ?? []).join(' + '),
    }));
  }
}
