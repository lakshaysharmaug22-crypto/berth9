export function statusTone(status: string | null | undefined): string {
  switch (status) {
    case 'VALID': case 'DELIVERED': case 'COMPLETED': case 'CLOSED': case 'HIGH': return 'ok';
    case 'WARNING': case 'NEEDS_REVIEW': case 'HALF_OPEN': case 'MEDIUM': return 'warn';
    case 'ERROR': case 'DEAD_LETTER': case 'FAILED': case 'REJECTED': case 'OPEN': case 'LOW': return 'err';
    case 'PROCESSING': return 'info';
    default: return '';
  }
}

export function label(status: string | null | undefined): string {
  return (status ?? '').replace(/_/g, ' ').toLowerCase();
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return '—';
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return `${Math.round(s)}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

export function num(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString('en-US', { maximumFractionDigits: 4 }) : String(v);
}

export function describeEvent(e: { type: string; data: any }): string {
  const d = e.data ?? {};
  switch (e.type) {
    case 'file.received': return `${d.partnerId} sent ${d.fileName} via ${d.channel}`;
    case 'file.detected': return `${d.partnerId}: detected ${d.format}, mapping spec v${d.specVersion}`;
    case 'file.progress': return `${d.partnerId}: ${d.records} records processed`;
    case 'file.processed': return `${d.partnerId}: ${d.total} records → ${d.valid} valid, ${d.warnings} warnings, ${d.errors} errors (${d.durationMs} ms)`;
    case 'file.failed': return `${d.partnerId}: ${d.fileName} failed: ${d.error}`;
    case 'file.duplicate': return `${d.partnerId}: ${d.fileName} is a duplicate of job ${d.jobId}, skipped`;
    case 'ack.sent': return `${d.partnerId}: 997 sent, ${d.accepted} accepted / ${d.rejected} rejected`;
    case 'delivery.succeeded': return `ERP accepted ${d.records} ${d.document} records (${d.latencyMs} ms)`;
    case 'delivery.failed': return `ERP delivery failed for ${d.records} records: ${d.error ?? 'error'}${d.deadLettered ? `, ${d.deadLettered} dead-lettered` : ''}`;
    case 'breaker.state': return `circuit breaker → ${d.state}`;
    case 'record.fixed': return `record ${d.recordId} fixed → ${d.status}`;
    case 'record.dismissed': return `record ${d.recordId} dismissed`;
    case 'record.replayed': return `record ${d.recordId} sent back to the outbox`;
    case 'spec.saved': return `${d.partnerId}: mapping spec v${d.version} saved`;
    case 'chaos.changed': return `ERP chaos: ${d.erpDown ? 'down' : 'up'}, +${d.latencyMs} ms, ${d.failRatePct}% failures`;
    case 'demo.started': return `demo run: dropping ${d.files} partner files into the inbox`;
    default: return e.type;
  }
}
