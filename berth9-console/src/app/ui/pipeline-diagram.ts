import { AfterViewInit, Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { Live, PipelineEvent } from '../core/live';

interface Node { id: string; label: string; sub: string; x: number; y: number; w: number; h: number; }
interface Partner { id: string; format: string; }
interface Particle { id: number; route: string; start: number; duration: number; tone: string; x: number; y: number; r: number; }

const STAGES = ['intake', 'detect', 'map', 'validate'];

/**
 * Animated view of the real pipeline. Every particle is a real event from the backend (or the recorded
 * run): a file arriving, being parsed, records splitting into delivered and exceptions, 997 acks going back.
 */
@Component({
  selector: 'b9-pipeline-diagram',
  templateUrl: './pipeline-diagram.html',
  styleUrl: './pipeline-diagram.css',
})
export class PipelineDiagram implements AfterViewInit, OnDestroy {
  private readonly live = inject(Live);
  readonly partners = input<Partner[]>([]);
  private readonly svg = viewChild<ElementRef<SVGSVGElement>>('svg');
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly narrow = signal(false);
  protected readonly particles = signal<Particle[]>([]);
  protected readonly active = signal<Record<string, number>>({});
  protected readonly breaker = this.live.breaker;
  private seq = 0;
  private frame = 0;
  private observer?: ResizeObserver;

  protected readonly viewBox = computed(() => (this.narrow() ? '0 0 360 700' : '0 0 1000 470'));

  protected readonly partnerNodes = computed(() => {
    const list = this.partners().length ? this.partners() : [];
    if (this.narrow()) {
      const gap = 340 / Math.max(list.length, 1);
      return list.map((p, i) => ({ ...p, x: 10 + gap * i + gap / 2, y: 36 }));
    }
    const gap = 410 / Math.max(list.length, 1);
    return list.map((p, i) => ({ ...p, x: 84, y: 30 + gap * i + gap / 2 }));
  });

  protected readonly nodes = computed<Node[]>(() => {
    if (this.narrow()) {
      return [
        { id: 'intake', label: 'Intake', sub: 'SFTP · API · folder', x: 180, y: 140, w: 200, h: 44 },
        { id: 'detect', label: 'Detect & parse', sub: 'format, header, layout', x: 180, y: 225, w: 200, h: 44 },
        { id: 'map', label: 'Map & enrich', sub: 'spec vN · FX · lookups', x: 180, y: 310, w: 200, h: 44 },
        { id: 'validate', label: 'Validate', sub: 'PO match · GSTIN · dupes', x: 180, y: 395, w: 200, h: 44 },
        { id: 'outbox', label: 'Outbox', sub: 'retry · idempotent', x: 95, y: 505, w: 160, h: 44 },
        { id: 'exceptions', label: 'Exceptions', sub: 'human review', x: 270, y: 505, w: 150, h: 44 },
        { id: 'erp', label: 'ERP / OMS', sub: 'REST · Kafka', x: 95, y: 620, w: 160, h: 44 },
      ];
    }
    return [
      { id: 'intake', label: 'Intake', sub: 'SFTP · API · folder', x: 300, y: 235, w: 116, h: 58 },
      { id: 'detect', label: 'Detect & parse', sub: 'format · header', x: 448, y: 235, w: 116, h: 58 },
      { id: 'map', label: 'Map & enrich', sub: 'spec · FX · lookups', x: 596, y: 235, w: 116, h: 58 },
      { id: 'validate', label: 'Validate', sub: 'PO match · rules', x: 744, y: 235, w: 116, h: 58 },
      { id: 'outbox', label: 'Outbox', sub: 'retry · idempotent', x: 890, y: 120, w: 150, h: 54 },
      { id: 'exceptions', label: 'Exceptions', sub: 'human review', x: 890, y: 360, w: 150, h: 54 },
      { id: 'erp', label: 'ERP / OMS', sub: 'REST · Kafka', x: 890, y: 34, w: 150, h: 44 },
    ];
  });

  protected readonly routes = computed(() => {
    const n = Object.fromEntries(this.nodes().map(x => [x.id, x]));
    const out: { key: string; d: string; dashed?: boolean }[] = [];
    const narrow = this.narrow();
    for (const p of this.partnerNodes()) {
      if (narrow) {
        out.push({ key: `in:${p.id}`, d: `M${p.x},${p.y + 14} C${p.x},${90} ${n['intake'].x},${80} ${n['intake'].x},${n['intake'].y - 22}` });
        out.push({ key: `ack:${p.id}`, d: `M${n['validate'].x - 100},${n['validate'].y} C${10},${n['validate'].y} ${p.x},${120} ${p.x},${p.y + 14}`, dashed: true });
      } else {
        out.push({ key: `in:${p.id}`, d: `M${p.x + 58},${p.y} C${190},${p.y} ${190},${n['intake'].y} ${n['intake'].x - 58},${n['intake'].y}` });
        out.push({ key: `ack:${p.id}`, d: `M${n['validate'].x},${n['validate'].y + 29} C${n['validate'].x},${440} ${220},${440} ${p.x + 58},${p.y + 6}`, dashed: true });
      }
    }
    for (let i = 0; i < STAGES.length - 1; i++) {
      const a = n[STAGES[i]], b = n[STAGES[i + 1]];
      out.push({ key: `${a.id}>${b.id}`, d: narrow ? `M${a.x},${a.y + 22} L${b.x},${b.y - 22}` : `M${a.x + 58},${a.y} L${b.x - 58},${b.y}` });
    }
    const v = n['validate'], o = n['outbox'], e = n['exceptions'], erp = n['erp'];
    if (narrow) {
      out.push({ key: 'validate>outbox', d: `M${v.x},${v.y + 22} C${v.x},${460} ${o.x},${440} ${o.x},${o.y - 22}` });
      out.push({ key: 'validate>exceptions', d: `M${v.x},${v.y + 22} C${v.x},${460} ${e.x},${440} ${e.x},${e.y - 22}` });
      out.push({ key: 'outbox>erp', d: `M${o.x},${o.y + 22} L${erp.x},${erp.y - 22}` });
    } else {
      out.push({ key: 'validate>outbox', d: `M${v.x + 58},${v.y - 10} C${820},${v.y - 10} ${815},${o.y} ${o.x - 75},${o.y}` });
      out.push({ key: 'validate>exceptions', d: `M${v.x + 58},${v.y + 10} C${820},${v.y + 10} ${815},${e.y} ${e.x - 75},${e.y}` });
      out.push({ key: 'outbox>erp', d: `M${o.x},${o.y - 27} L${erp.x},${erp.y + 22}` });
    }
    return out;
  });

  constructor() {
    effect(() => {
      const e = this.live.last();
      if (e) {
        queueMicrotask(() => this.onEvent(e));
      }
    });
  }

  ngAfterViewInit(): void {
    this.observer = new ResizeObserver(entries => this.narrow.set(entries[0].contentRect.width < 520));
    this.observer.observe(this.host.nativeElement);
    const tick = (now: number) => {
      this.step(now);
      this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
    this.observer?.disconnect();
  }

  protected nodeTone(id: string): string {
    if (id === 'erp' || id === 'outbox') {
      const b = this.breaker();
      if (b === 'OPEN') return 'err';
      if (b === 'HALF_OPEN') return 'warn';
    }
    return '';
  }

  private onEvent(e: PipelineEvent): void {
    const d = e.data ?? {};
    const p = d.partnerId as string | undefined;
    switch (e.type) {
      case 'file.received':
        this.emit(`in:${p}`, 'brand', 1, 900, 5);
        this.flash('intake');
        break;
      case 'file.duplicate':
        this.emit(`in:${p}`, 'muted', 1, 900, 4);
        break;
      case 'file.detected':
        this.emit('intake>detect', 'brand', 1, 500, 5);
        this.flash('detect');
        break;
      case 'file.processed': {
        this.emit('detect>map', 'brand', 1, 450, 5);
        setTimeout(() => { this.emit('map>validate', 'brand', 1, 450, 5); this.flash('map'); }, 380);
        setTimeout(() => {
          this.flash('validate');
          const good = Math.min(10, (d.valid ?? 0) + (d.warnings ?? 0));
          const bad = Math.min(6, d.errors ?? 0);
          this.emit('validate>outbox', 'ok', good, 900, 3.5, 70);
          this.emit('validate>exceptions', 'err', bad, 900, 3.5, 90);
          if (bad) this.flash('exceptions');
        }, 800);
        break;
      }
      case 'ack.sent':
        this.emit(`ack:${p}`, 'warn', 1, 1300, 4.5);
        break;
      case 'delivery.succeeded':
        this.emit('outbox>erp', 'ok', Math.min(8, d.records ?? 1), 600, 3.5, 60);
        this.flash('outbox');
        this.flash('erp');
        break;
      case 'delivery.failed':
        this.emit('outbox>erp', 'err', 2, 500, 4);
        this.flash('outbox');
        break;
      case 'record.fixed':
      case 'record.replayed':
        this.flash('exceptions');
        break;
    }
  }

  private emit(route: string, tone: string, count: number, duration: number, r: number, stagger = 0): void {
    const now = performance.now();
    const created: Particle[] = [];
    for (let i = 0; i < count; i++) {
      created.push({ id: ++this.seq, route, start: now + i * stagger, duration, tone, x: -50, y: -50, r });
    }
    if (created.length) this.particles.update(list => [...list, ...created].slice(-160));
  }

  private flash(node: string): void {
    this.active.update(a => ({ ...a, [node]: Date.now() }));
    setTimeout(() => this.active.update(a => {
      const { [node]: _, ...rest } = a;
      return rest;
    }), 700);
  }

  private step(now: number): void {
    const list = this.particles();
    if (!list.length) return;
    const root = this.svg()?.nativeElement;
    if (!root) return;
    const next: Particle[] = [];
    for (const p of list) {
      const t = (now - p.start) / p.duration;
      if (t > 1) continue;
      const path = root.querySelector<SVGPathElement>(`path[data-route="${CSS.escape(p.route)}"]`);
      if (!path) continue;
      if (t < 0) { next.push({ ...p, x: -50, y: -50 }); continue; }
      const eased = t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
      const pt = path.getPointAtLength(eased * path.getTotalLength());
      next.push({ ...p, x: pt.x, y: pt.y });
    }
    this.particles.set(next);
  }
}
