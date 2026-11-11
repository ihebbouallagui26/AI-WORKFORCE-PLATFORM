import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { PreferencesService } from '../core/services/preferences.service';

/** Below this width the sidebar collapses automatically to preserve content space. */
const NARROW_QUERY = '(max-width: 1100px)';

@Injectable({ providedIn: 'root' })
export class LayoutService {
  private readonly preferences = inject(PreferencesService);
  private readonly window = inject(DOCUMENT).defaultView;

  private readonly userCollapsed = signal(this.preferences.get('sidebarCollapsed'));
  private readonly narrow = signal(false);

  readonly sidebarCollapsed = computed(() => this.userCollapsed() || this.narrow());
  /** The user's own choice, independent of the automatic collapse on narrow windows. */
  readonly sidebarPreference = this.userCollapsed.asReadonly();
  readonly isNarrow = this.narrow.asReadonly();

  /** Label of the entity shown by a detail page, appended to the breadcrumb. */
  readonly detailTitle = signal<string | null>(null);

  constructor() {
    const media = this.window?.matchMedia?.(NARROW_QUERY);
    if (media) {
      this.narrow.set(media.matches);
      const listener = (event: MediaQueryListEvent) => this.narrow.set(event.matches);
      media.addEventListener('change', listener);
      inject(DestroyRef).onDestroy(() => media.removeEventListener('change', listener));
    }
  }

  toggleSidebar(): void {
    if (this.narrow()) {
      // On narrow windows the collapsed state is enforced; nothing to toggle.
      return;
    }
    this.setSidebarCollapsed(!this.userCollapsed());
  }

  setSidebarCollapsed(collapsed: boolean): void {
    this.userCollapsed.set(collapsed);
    this.preferences.set('sidebarCollapsed', collapsed);
  }
}
