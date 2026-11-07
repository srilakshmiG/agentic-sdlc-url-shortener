const LABELS: Record<string, string> = {
  RUNNING: 'Running',
  AWAITING_HUMAN: 'Needs you',
  COMPLETED: 'Completed',
  FAILED_ROLLED_BACK: 'Rolled back',
  SAFE_STOPPED: 'Safe-stopped',
  PENDING: 'Pending',
  AWAITING_APPROVAL: 'Needs approval',
  SUCCEEDED: 'Done',
  FAILED: 'Failed',
  ROLLED_BACK: 'Rolled back',
  SKIPPED: 'Skipped',
}

const TONE: Record<string, string> = {
  RUNNING: 'info', AWAITING_HUMAN: 'warn', COMPLETED: 'good', FAILED_ROLLED_BACK: 'bad', SAFE_STOPPED: 'bad',
  PENDING: '', AWAITING_APPROVAL: 'warn', SUCCEEDED: 'good', FAILED: 'bad', ROLLED_BACK: 'bad', SKIPPED: '',
}

export default function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${TONE[status] ?? ''}`}>{LABELS[status] ?? status}</span>
}
