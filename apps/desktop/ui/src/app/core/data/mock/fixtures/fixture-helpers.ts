import { IsoDateString, MemberRef } from '../../../models';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/**
 * Fixture timestamps are relative to "now" so the mock workspace always looks current.
 * The clock is captured once per seed to keep the dataset internally consistent.
 */
export class FixtureClock {
  constructor(private readonly now: number = Date.now()) {}

  minutesAgo(minutes: number): IsoDateString {
    return new Date(this.now - minutes * MINUTE).toISOString();
  }

  hoursAgo(hours: number): IsoDateString {
    return this.minutesAgo(hours * 60);
  }

  daysAgo(days: number): IsoDateString {
    return new Date(this.now - days * DAY).toISOString();
  }

  minutesFromNow(minutes: number): IsoDateString {
    return new Date(this.now + minutes * MINUTE).toISOString();
  }

  daysFromNow(days: number): IsoDateString {
    return new Date(this.now + days * DAY).toISOString();
  }
}

export const human = (id: string): MemberRef => ({ kind: 'human', id });
export const agent = (id: string): MemberRef => ({ kind: 'agent', id });
