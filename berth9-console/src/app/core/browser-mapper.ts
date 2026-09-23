/**
 * In-browser port of the engine's rules-based mapping suggester (dev.berth9.engine.suggest), used by the
 * public playground when no backend is attached. Same idea, smaller: header similarity after expanding
 * supplier abbreviations (60%) plus how well sampled values fit the field (40%), assigned one-to-one.
 */
export interface SchemaField { name: string; label: string; type: string; required: boolean; aliases?: string[]; kinds?: string[]; derived?: boolean; }
export interface BrowserSuggestion { field: string; sources: string[]; confidence: number; band: string; transform: string | null; reasons: string[]; origin: string; }

const ABBREV: Record<string, string> = {
  inv: 'invoice', invc: 'invoice', bill: 'invoice', no: 'number', num: 'number', nbr: 'number', '#': 'number',
  dt: 'date', qty: 'quantity', qnty: 'quantity', amt: 'amount', val: 'value', desc: 'description', descr: 'description',
  ccy: 'currency', curr: 'currency', ln: 'line', sr: 'serial', sl: 'serial', prc: 'price', ext: 'extended',
  itm: 'item', prod: 'product', vend: 'vendor', supp: 'supplier', ord: 'order', ref: 'reference',
};
const STOP = new Set(['of', 'the', 'a', 'an', 'in', 'for', 'per', 'by', 'to', 'rs', 'inr', 'usd', '₹', '$']);
const UOM = new Set(['EA', 'EACH', 'PCS', 'PC', 'NOS', 'UNIT', 'KG', 'KGS', 'BOX', 'BX', 'CS', 'CASE', 'CT', 'CTN', 'PK', 'PACK', 'SET', 'DZ', 'M', 'MTR', 'RL', 'ROLL', 'PR', 'BAG', 'BG', 'BD', 'L', 'LTR', 'LB']);
const CURRENCY = new Set(['USD', 'INR', 'EUR', 'GBP', '$', '₹', 'RS', 'RS.']);

export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [], cell = '', quoted = false;
  const delim = [',', ';', '\t', '|'].map(d => [d, (text.split('\n')[0] ?? '').split(d).length] as const).sort((a, b) => b[1] - a[1])[0][0];
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else quoted = false; } else cell += ch;
    } else if (ch === '"' && cell === '') quoted = true;
    else if (ch === delim) { row.push(cell); cell = ''; }
    else if (ch === '\n' || ch === '\r') { if (ch === '\r' && text[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; }
    else cell += ch;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.some(c => c.trim() !== ''));
}

function normalize(s: string): string {
  return s.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().replace(/[^a-z0-9#₹$]+/g, ' ').trim()
    .split(/\s+/).map(t => ABBREV[t] ?? t).join(' ').split(' ').filter(t => t && !STOP.has(t)).join(' ');
}

function similarity(a: string, b: string): number {
  if (!a || !b) return 0;
  if (a === b) return 1;
  const ta = new Set(a.split(' ')), tb = new Set(b.split(' '));
  const common = [...ta].filter(t => tb.has(t)).length;
  const dice = (2 * common) / (ta.size + tb.size);
  let contain = 0;
  if (common === tb.size) contain = 0.75 + (0.2 * tb.size) / Math.max(ta.size, tb.size);
  else if (common === ta.size) contain = 0.75 + (0.2 * ta.size) / Math.max(ta.size, tb.size);
  return Math.max(dice, contain);
}

const DATE = /^(\d{1,4}[-/.]\d{1,2}[-/.]\d{2,4}|\d{1,2}[- ][A-Za-z]{3,9}[- ,]+\d{2,4}|\d{4}-\d{2}-\d{2}T.*)$/;
function kinds(v: string): Set<string> {
  const s = v.trim(), u = s.toUpperCase(), k = new Set<string>();
  if (CURRENCY.has(u)) k.add('CURRENCY');
  if (UOM.has(u)) k.add('UOM');
  if (/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(u)) k.add('GSTIN');
  if (DATE.test(s)) k.add('DATE');
  const bare = s.replace(/^[($₹]|\s*(rs\.?|inr|usd)\s*/gi, '').replace(/[,)]/g, '');
  if (/^[+-]?\d+$/.test(bare)) { k.add('INTEGER'); k.add('DECIMAL'); k.add('AMOUNT'); }
  else if (/^[+-]?\d*\.\d+$/.test(bare)) { k.add('DECIMAL'); k.add('AMOUNT'); }
  if (!k.has('DATE') && !k.has('DECIMAL') && /\d/.test(s) && /^[A-Za-z0-9][A-Za-z0-9/_.#-]{2,30}$/.test(s)) k.add('CODE');
  if (!k.size) k.add('TEXT');
  return k;
}

function datePattern(values: string[], locale: string): { pattern: string; note?: string } {
  const parts = values.map(v => v.split(/[-/.]/).map(Number)).filter(p => p.length === 3);
  if (!parts.length || values.some(v => /[A-Za-z]/.test(v))) return { pattern: 'date' };
  const sep = values[0].match(/[-/.]/)?.[0] ?? '/';
  if (parts[0][0] > 31) return { pattern: `date(yyyy${sep}MM${sep}dd)` };
  const firstOver12 = parts.some(p => p[0] > 12), secondOver12 = parts.some(p => p[1] > 12);
  if (firstOver12) return { pattern: `date(dd${sep}MM${sep}yyyy)` };
  if (secondOver12) return { pattern: `date(MM${sep}dd${sep}yyyy)` };
  const us = locale === 'US';
  return { pattern: us ? `date(MM${sep}dd${sep}yyyy)` : `date(dd${sep}MM${sep}yyyy)`, note: `every sample date is ambiguous (day <= 12); assumed ${us ? 'month-first (US partner)' : 'day-first (IN partner)'}` };
}

export function suggest(fields: SchemaField[], rows: string[][], locale: string): { columns: string[]; suggestions: BrowserSuggestion[] } {
  const headerIdx = Math.max(0, rows.findIndex(r => r.filter(c => /[A-Za-z]/.test(c) && !/^\d/.test(c)).length >= Math.max(2, r.length * 0.6)));
  const columns = rows[headerIdx].map((c, i) => c.trim() || `column_${i + 1}`);
  const data = rows.slice(headerIdx + 1).filter(r => !/^(grand\s*)?total/i.test(r[0] ?? ''));
  const profiles = columns.map((col, i) => {
    const vals = data.map(r => (r[i] ?? '').trim()).filter(v => v !== '');
    const counts: Record<string, number> = {};
    vals.forEach(v => kinds(v).forEach(k => (counts[k] = (counts[k] ?? 0) + 1)));
    const share = (k: string) => (vals.length ? (counts[k] ?? 0) / vals.length : 0);
    return { col, vals, share };
  });
  const cands: { f: SchemaField; p: typeof profiles[number]; name: number; fit: number; score: number }[] = [];
  for (const f of fields.filter(f => !f.derived)) {
    const phrases = [f.name, f.label, ...(f.aliases ?? [])].map(normalize);
    const accept = f.kinds?.length ? f.kinds : f.type === 'DATE' ? ['DATE'] : f.type === 'DECIMAL' ? ['AMOUNT', 'DECIMAL'] : ['TEXT', 'CODE'];
    for (const p of profiles) {
      const name = Math.max(...phrases.map(ph => similarity(normalize(p.col), ph)));
      const fit = p.vals.length ? Math.max(...accept.map(k => p.share(k))) : 0;
      const strong = ['GSTIN', 'CURRENCY', 'UOM'].some(k => f.kinds?.includes(k) && p.share(k) >= 0.9);
      if (name < 0.3 && !strong) continue;
      let score = 0.6 * name + 0.4 * fit;
      if (name >= 0.95 && fit >= 0.5) score = Math.max(score, 0.92);
      if (fit < 0.2 && name < 0.95) score *= 0.5;
      if (strong) score = Math.max(score, 0.8);
      if (score >= 0.35) cands.push({ f, p, name, fit, score: Math.min(1, score) });
    }
  }
  cands.sort((a, b) => b.score - a.score);
  const chosen = new Map<string, typeof cands[number]>(), used = new Set<string>();
  for (const c of cands) {
    if (chosen.has(c.f.name) || used.has(c.p.col)) continue;
    chosen.set(c.f.name, c); used.add(c.p.col);
  }
  const suggestions = fields.filter(f => !f.derived).map(f => {
    const c = chosen.get(f.name);
    if (!c) return { field: f.name, sources: [], confidence: 0, band: 'LOW', transform: null, reasons: [`no column looks like ${f.label}`], origin: 'rules (in browser)' };
    const reasons = [`header '${c.p.col}' ~ '${f.label}' (${c.name.toFixed(2)})`, `${Math.round(c.fit * 100)}% of sampled values fit${c.p.vals[0] ? ` (e.g. '${c.p.vals[0]}')` : ''}`];
    let transform = 'trim';
    if (f.type === 'DATE') { const d = datePattern(c.p.vals, locale); transform = d.pattern; if (d.note) reasons.push(d.note); }
    else if (f.kinds?.includes('CURRENCY')) transform = 'currency';
    else if (f.kinds?.includes('UOM')) transform = 'upper | lookup(uom, *)';
    else if (f.type === 'DECIMAL') transform = f.kinds?.includes('AMOUNT') ? 'amount' : 'number';
    else if (f.type === 'INTEGER') transform = 'number';
    else if (f.kinds?.includes('CODE') || f.kinds?.includes('GSTIN')) transform = 'trim | upper';
    else if (f.kinds?.includes('TEXT')) transform = 'collapse';
    const confidence = Math.round(c.score * 100) / 100;
    return { field: f.name, sources: [c.p.col], confidence, band: confidence >= 0.8 ? 'HIGH' : confidence >= 0.55 ? 'MEDIUM' : 'LOW', transform, reasons, origin: 'rules (in browser)' };
  });
  return { columns, suggestions };
}
