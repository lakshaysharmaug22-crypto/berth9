import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Api } from '../core/api';
import { statusTone } from '../core/format';
import { parseCsv, suggest, SchemaField } from '../core/browser-mapper';

interface Suggestion { field: string; sources: string[]; join?: string | null; constant?: string | null; confidence: number; band: string; transform: string | null; reasons: string[]; origin: string; }

@Component({
  selector: 'b9-mapping',
  template: `
  <div class="page">
    <div class="page-head">
      <div>
        <h1>Mapping studio</h1>
        <p>Onboard a new partner in minutes: drop a sample file and Berth 9 profiles every column, then proposes a mapping to the canonical schema with a confidence score and the reasons behind it. A rules engine settles most fields; only the uncertain ones are sent to a local LLM.</p>
      </div>
    </div>

    <section class="card card-pad stack">
      <div class="row wrap gap">
        <label class="field">Target schema
          <select [value]="schema()" (change)="schema.set($any($event.target).value)">
            <option value="invoice-line">Supplier invoice line</option>
            <option value="order-line">Customer PO line</option>
          </select>
        </label>
        <label class="field">Partner locale
          <select [value]="locale()" (change)="locale.set($any($event.target).value)">
            <option value="US">US (month-first dates)</option>
            <option value="IN">India (day-first dates)</option>
          </select>
        </label>
        @if (api.mode() === 'live') {
          <label class="field">Record element <span class="faint">(XML / JSON / EDI only)</span>
            <input [value]="recordPath()" (input)="recordPath.set($any($event.target).value)" placeholder="e.g. InvoiceDetailItem, lines, IT1" />
          </label>
        }
        <label class="btn primary upload">
          <input type="file" (change)="upload($any($event.target).files?.[0])" hidden
                 [attr.accept]="api.mode() === 'live' ? '.csv,.txt,.xlsx,.xml,.json,.x12,.edi' : '.csv,.txt'" />
          Upload sample file
        </label>
      </div>
      <p class="muted small">
        @if (api.mode() === 'live') { Suggestions come from the Java engine (plus the LLM advisor when it is enabled). }
        @else { Recorded demo: try your own CSV. It is profiled right here in your browser with a TypeScript port of the engine's rules matcher; nothing is uploaded. }
      </p>
      @if (error()) { <div class="toast err">{{ error() }}</div> }
    </section>

    @if (result(); as r) {
      <section class="card">
        <div class="card-pad row wrap gap between">
          <div><strong>{{ r.fileName }}</strong> <span class="faint">· {{ r.columns.length }} columns · {{ r.records }} sample rows</span></div>
          <div class="row gap">
            <span class="pill ok">{{ counts().HIGH }} high</span>
            <span class="pill warn">{{ counts().MEDIUM }} review</span>
            <span class="pill err">{{ counts().LOW }} unmatched</span>
          </div>
        </div>
        <div class="table-wrap">
          <table class="grid">
            <thead><tr><th>Target field</th><th>Partner column</th><th class="hide-sm">Transform</th><th>Confidence</th><th class="hide-sm">Why</th></tr></thead>
            <tbody>
              @for (s of r.suggestions; track s.field) {
                <tr>
                  <td><strong>{{ s.field }}</strong></td>
                  <td class="mono">{{ s.sources.length ? s.sources.join(s.join === '+' ? ' + ' : ', ') : (s.constant ? '= ' + s.constant : '—') }}</td>
                  <td class="mono hide-sm">{{ s.transform ?? '' }}</td>
                  <td>
                    <div class="conf"><div class="bar"><span [style.width.%]="s.confidence * 100" [class]="tone(s.band)"></span></div>
                    <span [class]="'pill ' + tone(s.band)">{{ (s.confidence * 100).toFixed(0) }}%</span></div>
                    <div class="faint tiny">{{ s.origin }}</div>
                  </td>
                  <td class="hide-sm reasons">@for (why of s.reasons; track $index) { <div>{{ why }}</div> }</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }
  </div>`,
  styles: [`
    .field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-2); }
    .field select, .field input { min-width: 200px; }
    .wrap { flex-wrap: wrap; } .gap { gap: 12px; } .between { justify-content: space-between; } .small { font-size: 13px; margin: 0; } .tiny { font-size: 11px; }
    .upload { align-self: flex-end; cursor: pointer; }
    .conf { display: flex; align-items: center; gap: 8px; }
    .bar { width: 90px; height: 6px; border-radius: 3px; background: var(--bg-sunk); overflow: hidden; }
    .bar span { display: block; height: 100%; transition: width .6s ease; background: var(--brand); }
    .bar span.ok { background: var(--ok); } .bar span.warn { background: var(--warn); } .bar span.err { background: var(--err); }
    .reasons { font-size: 12px; color: var(--text-2); max-width: 420px; }
  `],
})
export class MappingPage implements OnInit {
  protected readonly api = inject(Api);
  protected readonly schema = signal('invoice-line');
  protected readonly locale = signal('US');
  protected readonly recordPath = signal('');
  protected readonly result = signal<{ fileName: string; columns: string[]; records: number; suggestions: Suggestion[] } | null>(null);
  protected readonly error = signal('');
  protected readonly tone = statusTone;
  protected readonly counts = computed(() => {
    const c = { HIGH: 0, MEDIUM: 0, LOW: 0 } as { HIGH: number; MEDIUM: number; LOW: number };
    this.result()?.suggestions.forEach(s => c[s.band as 'HIGH' | 'MEDIUM' | 'LOW']++);
    return c;
  });
  private schemas: Record<string, { fields: SchemaField[] }> = {};

  async ngOnInit(): Promise<void> {
    this.schemas = await this.api.get('/api/schemas');
    const recorded = this.api.mode() === 'replay' ? this.api.replayData?.mapping : null;
    if (recorded) {
      this.result.set({ fileName: 'coastal_chem_invoice_lines.csv (new supplier, recorded)', columns: recorded.columns, records: recorded.records, suggestions: recorded.suggestions });
    }
  }

  protected async upload(file: File | undefined): Promise<void> {
    if (!file) return;
    this.error.set('');
    try {
      if (this.api.mode() === 'live') {
        const form = new FormData();
        form.append('file', file);
        form.append('schema', this.schema());
        form.append('locale', this.locale());
        if (this.recordPath()) form.append('record', this.recordPath());
        const r = await this.api.send<any>('POST', '/api/mapping/suggest', undefined, form);
        this.result.set({ fileName: file.name, columns: r.columns, records: r.records, suggestions: r.suggestions });
      } else {
        const rows = parseCsv(await file.text());
        if (rows.length < 2) throw new Error('The file needs a header row and at least one data row.');
        const out = suggest(this.schemas[this.schema()].fields, rows, this.locale());
        this.result.set({ fileName: file.name, columns: out.columns, records: rows.length - 1, suggestions: out.suggestions });
      }
    } catch (e: any) {
      this.error.set(e?.message ?? String(e));
    }
  }
}
