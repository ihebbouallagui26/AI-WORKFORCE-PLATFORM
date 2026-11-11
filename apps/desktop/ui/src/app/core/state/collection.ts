import { computed, Signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { Id } from '../models';

type Updater<T> = (items: readonly T[]) => readonly T[];

/** Read model of a list, with loading and error states. */
export interface Collection<T> {
  readonly items: Signal<readonly T[]>;
  readonly loading: Signal<boolean>;
  readonly error: Signal<string | null>;
  reload(): void;
}

/** Global list: local changes always apply to the single dataset. */
export interface GlobalCollection<T> extends Collection<T> {
  /** Applies a local change after a successful mutation. */
  mutate(updater: Updater<T>): void;
}

/** Organization-scoped list. */
export interface OrgCollection<T> extends Collection<T> {
  /**
   * Applies a local change produced by a request issued for `organizationId`.
   *
   * The change is dropped when another organization is selected, or while the list is
   * (re)loading: writing into a loading resource would cancel the request and display
   * data from the wrong organization. The pending load returns the server state, which
   * already contains the change.
   */
  mutate(organizationId: Id, updater: Updater<T>): boolean;
}

/**
 * Loads a list for the current organization and reloads it automatically when the
 * organization changes. Responses of a previous organization are discarded by the
 * resource (the stream is unsubscribed when the parameters change).
 * Must be called in an injection context.
 */
export function orgCollection<T>(
  organizationId: Signal<Id | null>,
  load: (organizationId: Id) => Observable<T[]>,
): OrgCollection<T> {
  const resource = rxResource<readonly T[], Id | undefined>({
    params: () => organizationId() ?? undefined,
    stream: ({ params }) => load(params),
    defaultValue: [],
  });

  return {
    items: computed(() => (resource.hasValue() ? resource.value() : [])),
    loading: resource.isLoading,
    error: computed(() => {
      const error = resource.error();
      return error ? error.message || 'Unexpected error while loading data.' : null;
    }),
    reload: () => {
      resource.reload();
    },
    mutate: (targetOrganizationId, updater) =>
      untracked(() => {
        const status = resource.status();
        const ready = status === 'resolved' || status === 'local';
        if (!ready || organizationId() !== targetOrganizationId) {
          return false;
        }
        resource.value.update(updater);
        return true;
      }),
  };
}

/** Same as {@link orgCollection} for global (non organization-scoped) lists. */
export function globalCollection<T>(load: () => Observable<T[]>): GlobalCollection<T> {
  const resource = rxResource<readonly T[], true>({
    params: () => true,
    stream: () => load(),
    defaultValue: [],
  });

  return {
    items: computed(() => (resource.hasValue() ? resource.value() : [])),
    loading: resource.isLoading,
    error: computed(() => resource.error()?.message ?? null),
    reload: () => {
      resource.reload();
    },
    mutate: (updater) => {
      if (resource.hasValue()) {
        resource.value.update(updater);
      }
    },
  };
}

export function upsertById<T extends { readonly id: Id }>(items: readonly T[], item: T): readonly T[] {
  const index = items.findIndex((existing) => existing.id === item.id);
  if (index < 0) {
    return [...items, item];
  }
  const next = [...items];
  next[index] = item;
  return next;
}

export function indexById<T extends { readonly id: Id }>(items: readonly T[]): ReadonlyMap<Id, T> {
  return new Map(items.map((item) => [item.id, item]));
}

/** Current organization, or an explicit error for mutations that require one. */
export function requireOrganization(organizationId: Signal<Id | null>): Id {
  const id = organizationId();
  if (!id) {
    throw new Error('No organization selected.');
  }
  return id;
}
