import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { ActivityEvent } from '../../core/models';
import { ToastService } from '../../core/services/toast.service';
import { SupervisionStore } from '../../core/state/supervision.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { Avatar } from '../../shared/components/avatar/avatar';
import { Dropdown } from '../../shared/components/dropdown/dropdown';
import { Icon } from '../../shared/components/icon/icon';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { ACTIVITY_EVENT } from '../../shared/utils/presentation';
import { LayoutService } from '../layout.service';
import { GlobalSearch } from './global-search';

interface Crumb {
  readonly label: string;
  readonly link: string | null;
}

@Component({
  selector: 'app-topbar',
  imports: [RouterLink, Icon, Avatar, Dropdown, GlobalSearch, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly supervision = inject(SupervisionStore);
  private readonly layout = inject(LayoutService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly activityPresentation = ACTIVITY_EVENT;

  /** Route section (title + link) resolved from the deepest route carrying `data.title`. */
  private readonly section = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.resolveSection()),
    ),
    { initialValue: { title: '', link: null as string | null } },
  );

  protected readonly crumbs = computed<readonly Crumb[]>(() => {
    const section = this.section();
    const detail = this.layout.detailTitle();
    const crumbs: Crumb[] = [{ label: this.workspace.currentOrganization()?.name ?? 'Workspace', link: '/dashboard' }];
    if (section.title) {
      crumbs.push({ label: section.title, link: detail ? section.link : null });
    }
    if (detail) {
      crumbs.push({ label: detail, link: null });
    }
    return crumbs;
  });

  protected readonly recentActivity = computed(() => this.supervision.activity().slice(0, 6));

  protected openEvent(event: ActivityEvent): void {
    const tab = event.type === 'approval-requested' ? 'approvals' : 'feed';
    void this.router.navigate(['/activity-center'], { queryParams: { tab } });
  }

  protected async markAllRead(): Promise<void> {
    try {
      await this.supervision.markAllActivityRead();
    } catch (error) {
      this.toast.error('Could not update notifications', error);
    }
  }

  private resolveSection(): { title: string; link: string | null } {
    let current: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let title = '';
    let link: string | null = null;
    const segments: string[] = [];
    while (current) {
      segments.push(...current.url.map((s) => s.path));
      const data = current.data;
      if (typeof data['title'] === 'string') {
        title = data['title'];
        link = '/' + segments.filter(Boolean).join('/');
      }
      current = current.firstChild;
    }
    return { title, link };
  }
}
