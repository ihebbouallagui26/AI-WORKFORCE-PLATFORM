import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MemberRef, Project, ProjectCategory, ProjectStatus } from '../../core/models';
import { ProjectStore } from '../../core/state/project.store';
import { AvatarStack } from '../../shared/components/avatar-stack/avatar-stack';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Member } from '../../shared/components/member/member';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { ProgressBar } from '../../shared/components/progress-bar/progress-bar';
import { SearchInput } from '../../shared/components/search-input/search-input';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { PROJECT_CATEGORY, PROJECT_STATUS } from '../../shared/utils/presentation';

const STATUSES = Object.keys(PROJECT_STATUS) as ProjectStatus[];
const CATEGORIES = Object.keys(PROJECT_CATEGORY) as ProjectCategory[];

@Component({
  selector: 'app-projects-page',
  imports: [DatePipe, RouterLink, PageHeader, SearchInput, Badge, Member, AvatarStack, ProgressBar, Icon, Skeleton, EmptyState],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './projects-page.html',
  styleUrl: './projects-page.scss',
})
export class ProjectsPage {
  protected readonly store = inject(ProjectStore);
  protected readonly statuses = PROJECT_STATUS;
  protected readonly categories = PROJECT_CATEGORY;
  protected readonly statusOptions = STATUSES;
  protected readonly categoryOptions = CATEGORIES;

  protected readonly query = signal('');
  protected readonly status = signal<ProjectStatus | null>(null);
  protected readonly category = signal<ProjectCategory | null>(null);

  protected readonly filtered = computed(() => {
    const query = this.query().trim().toLowerCase();
    return this.store
      .projects()
      .filter(
        (p) =>
          (!this.status() || p.status === this.status()) &&
          (!this.category() || p.category === this.category()) &&
          (!query || `${p.name} ${p.code} ${p.description}`.toLowerCase().includes(query)),
      )
      .sort((a, b) => Number(a.status === 'completed') - Number(b.status === 'completed') || a.dueDate.localeCompare(b.dueDate));
  });

  protected readonly filtersActive = computed(() => !!(this.query() || this.status() || this.category()));

  protected setStatus(value: string): void {
    this.status.set(STATUSES.includes(value as ProjectStatus) ? (value as ProjectStatus) : null);
  }

  protected setCategory(value: string): void {
    this.category.set(CATEGORIES.includes(value as ProjectCategory) ? (value as ProjectCategory) : null);
  }

  protected clear(): void {
    this.query.set('');
    this.status.set(null);
    this.category.set(null);
  }

  protected memberRefs(project: Project): readonly MemberRef[] {
    return project.members.map((m) => m.member);
  }

  protected isOverdue(dueDate: string, status: ProjectStatus): boolean {
    return status !== 'completed' && new Date(dueDate).getTime() < Date.now();
  }
}
