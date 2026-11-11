import { Pipe, PipeTransform } from '@angular/core';

/** Duration between two timestamps (or until now when `end` is null), e.g. "4m 12s", "1h 05m". */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(start: string, end: string | null, now: number = Date.now()): string {
    const ms = (end ? new Date(end).getTime() : now) - new Date(start).getTime();
    if (!Number.isFinite(ms) || ms < 0) {
      return '—';
    }
    const totalSeconds = Math.round(ms / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return `${hours}h ${String(minutes).padStart(2, '0')}m`;
    }
    if (minutes > 0) {
      return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
    }
    return `${seconds}s`;
  }
}
