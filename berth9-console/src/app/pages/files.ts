import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { ago, label, num, statusTone } from '../core/format';

@Component({
  selector: 'b9-files',
  imports: [RouterLink],
  template: `
  <div class="page">
    <div class="page-head">
      <div>
        <h1>Files</h1>
        <p>Every file received from every channel. Re-sent files with identical content are recognised by SHA-256 and never processed twice.</p>
      </div>
      <div class="row">
        <select (change)="filter($any($event.target).value)" aria-label="Filter by partner">
          <option value="">All partners</option>
          @for (p of partners(); track p.id) { <option [value]="p.id">{{ p.name }}</option> }
        </select>
      </div>
    </div>
    <section class="card">
      <div class="table-wrap">
        <table class="grid">
          <thead><tr><th>#</th><th>Partner</th><th>File</th><th class="hide-sm">Channel</th><th class="hide-sm">Format</th><th>Records</th><th>Status</th><th class="hide-sm">Time</th><th class="hide-sm">Received</th></tr></thead>
          <tbody>
            @for (j of jobs(); track j.id) {
              <tr class="clickable" [routerLink]="['/files', j.id]">
                <td class="faint">{{ j.id }}</td>
                <td><strong>{{ j.partnerId }}</strong></td>
                <td class="mono ellipsis">{{ j.fileName }}</td>
                <td class="hide-sm"><span class="pill">{{ j.channel }}</span></td>
                <td class="hide-sm"><span class="pill brand">{{ j.format }}</span></td>
                <td class="nowrap">{{ j.total }}
                  @if (j.warnings) { <span class="pill warn">{{ j.warnings }}</span> }
                  @if (j.errors) { <span class="pill err">{{ j.errors }}</span> }
                </td>
                <td><span [class]="'pill ' + tone(j.status)">{{ label(j.status) }}</span></td>
                <td class="hide-sm faint">{{ num(j.durationMs) }} ms</td>
                <td class="hide-sm faint nowrap">{{ ago(j.receivedAt) }}</td>
              </tr>
            } @empty {
              <tr><td colspan="9" class="empty">No files yet. Run the demo from the pipeline page.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  </div>`,
  styles: [`.ellipsis { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .nowrap { white-space: nowrap; } select { min-width: 200px; }`],
})
export class FilesPage implements OnInit {
  private readonly api = inject(Api);
  protected readonly jobs = signal<any[]>([]);
  protected readonly partners = signal<any[]>([]);
  protected readonly ago = ago;
  protected readonly num = num;
  protected readonly tone = statusTone;
  protected readonly label = label;

  async ngOnInit(): Promise<void> {
    this.partners.set(await this.api.get('/api/partners'));
    await this.filter('');
  }

  protected async filter(partner: string): Promise<void> {
    this.jobs.set(await this.api.get('/api/jobs?limit=200' + (partner ? '&partner=' + partner : '')));
  }
}
