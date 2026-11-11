import { isAllowedExternalUrl } from '../../core/desktop/desktop.service';
import { toInitials } from '../components/avatar/avatar';
import { DurationPipe } from './duration.pipe';
import { RelativeTimePipe } from './relative-time.pipe';

describe('RelativeTimePipe', () => {
  const pipe = new RelativeTimePipe();
  const now = new Date('2026-11-10T12:00:00Z').getTime();
  const at = (offsetMs: number) => new Date(now + offsetMs).toISOString();

  it('formats past and future offsets', () => {
    expect(pipe.transform(at(-20_000), now)).toBe('just now');
    expect(pipe.transform(at(-12 * 60_000), now)).toBe('12 min ago');
    expect(pipe.transform(at(3 * 3_600_000), now)).toBe('in 3 h');
    expect(pipe.transform(at(-86_400_000), now)).toBe('1 day ago');
    expect(pipe.transform(at(2 * 86_400_000), now)).toBe('in 2 days');
  });

  it('handles empty and invalid values', () => {
    expect(pipe.transform(null, now)).toBe('—');
    expect(pipe.transform('not a date', now)).toBe('—');
  });
});

describe('DurationPipe', () => {
  const pipe = new DurationPipe();

  it('formats seconds, minutes and hours', () => {
    expect(pipe.transform('2026-01-01T00:00:00Z', '2026-01-01T00:00:42Z')).toBe('42s');
    expect(pipe.transform('2026-01-01T00:00:00Z', '2026-01-01T00:04:12Z')).toBe('4m 12s');
    expect(pipe.transform('2026-01-01T00:00:00Z', '2026-01-01T01:05:00Z')).toBe('1h 05m');
  });
});

describe('toInitials', () => {
  it('derives initials for single and multi-word names', () => {
    expect(toInitials('Atlas')).toBe('AT');
    expect(toInitials('Leila Ben Salem')).toBe('LS');
    expect(toInitials('  ')).toBe('?');
  });
});

describe('isAllowedExternalUrl', () => {
  it('only allows https URLs on approved hosts', () => {
    expect(isAllowedExternalUrl('https://github.com/ihebbouallagui26/AI-WORKFORCE-PLATFORM')).toBe(true);
    expect(isAllowedExternalUrl('http://github.com/')).toBe(false);
    expect(isAllowedExternalUrl('https://evil.example/')).toBe(false);
    expect(isAllowedExternalUrl('javascript:alert(1)')).toBe(false);
    expect(isAllowedExternalUrl('not a url')).toBe(false);
  });
});
