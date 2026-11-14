/**
 * Demo scope for domains the Core API does not implement yet (agents, projects, communication,
 * supervision, departments). In `api` mode these keep using the in-memory demo dataset, always
 * read from the demo organization whatever real organization is selected, and the UI labels
 * them as demo data. Nothing is sent to the backend and no real data is ever mixed in.
 */
export const DEMO_ORGANIZATION_ID = 'org-northwind';
export const DEMO_EMPLOYEE_ID = 'emp-leila';

/**
 * Wraps a mock API so that every call taking an organization id as first argument reads the
 * demo organization instead.
 */
export function demoScoped<T extends object>(target: T, organizationScopedMethods: readonly (keyof T & string)[]): T {
  const scoped = new Set<string>(organizationScopedMethods);
  return new Proxy(target, {
    get(object, property, receiver) {
      const value = Reflect.get(object, property, receiver);
      if (typeof property !== 'string' || !scoped.has(property) || typeof value !== 'function') {
        return typeof value === 'function' ? value.bind(object) : value;
      }
      return (_organizationId: unknown, ...rest: unknown[]) => value.call(object, DEMO_ORGANIZATION_ID, ...rest);
    },
  });
}
