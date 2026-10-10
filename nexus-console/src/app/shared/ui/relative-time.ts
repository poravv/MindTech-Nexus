const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Compact age for pills ("5 min", "3 h", "2 d", "4 meses"), like the age badges of Argo CD. */
export function relativeTime(iso: string, now = Date.now()): string {
  const elapsed = Math.max(0, now - new Date(iso).getTime());
  if (elapsed < MINUTE) {
    return 'ahora';
  }
  if (elapsed < HOUR) {
    return `${Math.floor(elapsed / MINUTE)} min`;
  }
  if (elapsed < DAY) {
    return `${Math.floor(elapsed / HOUR)} h`;
  }
  const days = Math.floor(elapsed / DAY);
  if (days < 30) {
    return `${days} d`;
  }
  const months = Math.floor(days / 30);
  return months < 12 ? `${months} ${months === 1 ? 'mes' : 'meses'}` : `${Math.floor(months / 12)} a`;
}
