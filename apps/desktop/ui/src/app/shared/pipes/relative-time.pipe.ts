import { Pipe, PipeTransform } from '@angular/core';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** "just now", "12 min ago", "in 3 h", then a short date beyond a week. */
@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  transform(value: string | null | undefined, now: number = Date.now()): string {
    if (!value) {
      return '—';
    }
    const time = new Date(value).getTime();
    if (Number.isNaN(time)) {
      return '—';
    }
    const diff = time - now;
    const abs = Math.abs(diff);
    const future = diff > 0;

    if (abs < MINUTE) {
      return future ? 'in a moment' : 'just now';
    }
    let label: string;
    if (abs < HOUR) {
      label = `${Math.round(abs / MINUTE)} min`;
    } else if (abs < DAY) {
      label = `${Math.round(abs / HOUR)} h`;
    } else if (abs < 7 * DAY) {
      const days = Math.round(abs / DAY);
      label = `${days} ${days === 1 ? 'day' : 'days'}`;
    } else {
      return new Date(time).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    }
    return future ? `in ${label}` : `${label} ago`;
  }
}
