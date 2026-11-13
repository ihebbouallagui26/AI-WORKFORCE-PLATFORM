import { computed, resource, Signal } from '@angular/core';

export interface OrgResource<T> {
  /** The loaded value, or null while loading, disabled or after an error. Never another organization's data. */
  readonly value: Signal<T | null>;
  readonly loading: Signal<boolean>;
  readonly error: Signal<string | null>;
  reload(): void;
}

/**
 * A loader keyed by the current organization (and an optional query key).
 *
 * Each result is tagged with the organization it was requested for, and only exposed while that
 * organization is still the current one: a slow response arriving after an organization switch can
 * never show another organization's data. The request itself is also cancelled by the resource
 * when the key changes.
 */
export function orgResource<T, K = null>(
  organizationId: Signal<string | null>,
  enabled: () => boolean,
  key: () => K,
  load: (organizationId: string, key: K) => Promise<T>,
): OrgResource<T> {
  const res = resource<{ organizationId: string; data: T } | undefined, { id: string; key: K } | undefined>({
    params: () => {
      const id = organizationId();
      return id && enabled() ? { id, key: key() } : undefined;
    },
    loader: async ({ params }) => ({ organizationId: params.id, data: await load(params.id, params.key) }),
  });
  return {
    value: computed(() => {
      const current = res.hasValue() ? res.value() : undefined;
      return current && current.organizationId === organizationId() ? current.data : null;
    }),
    loading: res.isLoading,
    error: computed(() => res.error()?.message ?? null),
    reload: () => {
      res.reload();
    },
  };
}
