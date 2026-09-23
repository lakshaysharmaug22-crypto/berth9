import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { ago, label, statusTone } from '../core/format';

@Component({
  selector: 'b9-partners',
  imports: [RouterLink],
  template: `
  <div class="page">
    <div class="page-head">
      <div>
        <h1>Partners</h1>
        <p>Trading partners, the channel and format each one uses, and the versioned mapping spec that turns their files into canonical records.</p>
      </div>
    </div>
    <div class="partner-grid">
      @for (p of partners(); track p.id) {
        <section class="card card-pad partner" [class.active]="selected()?.id === p.id" (click)="open(p.id)">
          <div class="row between">
            <strong>{{ p.name }}</strong>
            <span class="pill" [class.brand]="p.role === 'SUPPLIER'">{{ p.role === 'SUPPLIER' ? 'supplier' : 'customer' }}</span>
          </div>
          <div class="faint small">{{ p.city }} · {{ p.country === 'IN' ? 'India' : 'United States' }}</div>
          <div class="row wrap tags">
            <span class="pill">{{ p.channel }}</span><span class="pill brand">{{ p.document }}</span><span class="pill">{{ p.currency }}</span>
          </div>
          <div class="faint small">SLA: file by {{ p.expectedBy }} · last file {{ ago(p.lastFileAt) }}</div>
        </section>
      }
    </div>

    @if (selected(); as d) {
      <section class="card card-pad stack">
        <div class="row between wrap">
          <h2>{{ d.name }} <span class="faint">({{ d.id }})</span></h2>
          @if (d.gstin) { <span class="mono faint">GSTIN {{ d.gstin }}</span> }
          @if (d.ediId) { <span class="mono faint">EDI ID {{ d.ediId }}</span> }
        </div>
        @for (s of d.specs; track s.version) {
          <div>
            <div class="row between"><strong>Mapping spec v{{ s.version }}</strong><span class="faint small">{{ s.note }} · {{ ago(s.createdAt) }}</span></div>
            <div class="table-wrap">
              <table class="grid">
                <thead><tr><th>Target field</th><th>From</th><th>Transform</th></tr></thead>
                <tbody>
                  @for (f of s.spec.fields; track f.target) {
                    <tr><td><strong>{{ f.target }}</strong></td>
                      <td class="mono">{{ f.const ? '= ' + f.const : (f.sources ? f.sources.join(f.join === '+' ? ' + ' : ', ') : f.source) }}</td>
                      <td class="mono faint">{{ f.transform ?? '' }}</td></tr>
                  }
                  @for (e of s.spec.enrich ?? []; track $index) {
                    <tr><td><strong>{{ e.target }}</strong></td><td class="mono" colspan="2">{{ e.type === 'compute' ? e.left + ' ' + e.op + ' ' + e.right : e.type === 'convert' ? 'convert ' + e.amount + ' to ' + e.base : e.type }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
        <div>
          <strong>Recent files</strong>
          <div class="table-wrap">
            <table class="grid">
              <tbody>
                @for (j of d.recentJobs; track j.id) {
                  <tr class="clickable" [routerLink]="['/files', j.id]"><td class="mono">{{ j.fileName }}</td><td>{{ j.total }} records</td>
                    <td><span [class]="'pill ' + tone(j.status)">{{ label(j.status) }}</span></td><td class="faint">{{ ago(j.receivedAt) }}</td></tr>
                } @empty { <tr><td class="empty">No files from this partner yet.</td></tr> }
              </tbody>
            </table>
          </div>
        </div>
      </section>
    }
  </div>`,
  styles: [`
    .partner-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; margin-bottom: 16px; }
    .partner { cursor: pointer; display: flex; flex-direction: column; gap: 8px; transition: border-color .2s, transform .2s; }
    .partner:hover { transform: translateY(-2px); } .partner.active { border-color: var(--brand); }
    .between { justify-content: space-between; } .wrap { flex-wrap: wrap; } .tags { gap: 6px; } .small { font-size: 12px; }
    h2 { margin: 0; font-size: 18px; }
  `],
})
export class PartnersPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly partners = signal<any[]>([]);
  protected readonly selected = signal<any | null>(null);
  protected readonly ago = ago;
  protected readonly tone = statusTone;
  protected readonly label = label;

  async ngOnInit(): Promise<void> {
    const list = await this.api.get<any[]>('/api/partners');
    this.partners.set(list);
    if (list.length) await this.open(list[0].id);
  }

  protected async open(id: string): Promise<void> {
    this.selected.set(await this.api.get('/api/partners/' + id));
  }
}
