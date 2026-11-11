import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OrganizationSwitchService } from '../../core/services/organization-switch.service';
import { ToastService } from '../../core/services/toast.service';
import { ProjectStore } from '../../core/state/project.store';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { Avatar } from '../../shared/components/avatar/avatar';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';
import { RelativeTimePipe } from '../../shared/pipes/relative-time.pipe';
import { TASK_STATUS } from '../../shared/utils/presentation';
import { errorMessage, notBlank } from '../../shared/utils/validators';

@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, RouterLink, PageHeader, Panel, Avatar, Badge, EmptyState, RelativeTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Profile" description="Your account and the work assigned to you in this organization." />
      @if (workspace.user(); as user) {
        <div class="grid grid--main">
          <div class="stack">
            <ui-panel heading="Account">
              <div class="identity">
                <ui-avatar size="xl" [name]="user.displayName" [tone]="user.avatarTone" status="online" />
                <div>
                  <p class="identity__name">{{ user.displayName }}</p>
                  <p class="identity__title">{{ user.title }}</p>
                  <p class="identity__email">{{ user.email }}</p>
                </div>
              </div>
              <form class="form-grid" [formGroup]="form" (ngSubmit)="save()" novalidate>
                <div class="field">
                  <label class="field__label" for="profile-name">Display name<span class="field__required" aria-hidden="true">*</span></label>
                  <input id="profile-name" class="input" formControlName="displayName" autocomplete="name" />
                  @if (error('displayName', 'Display name'); as message) {
                    <span class="field__error">{{ message }}</span>
                  }
                </div>
                <div class="field">
                  <label class="field__label" for="profile-title">Title</label>
                  <input id="profile-title" class="input" formControlName="title" autocomplete="organization-title" />
                </div>
                <div class="field field--full actions">
                  <button type="submit" class="btn btn--primary" [disabled]="form.pristine || saving()">Save profile</button>
                </div>
              </form>
            </ui-panel>

            <ui-panel heading="My open tasks" [subheading]="myTasks().length + ' in ' + (workspace.currentOrganization()?.name ?? '')" [flush]="true">
              <ul>
                @for (task of myTasks(); track task.id) {
                  <li>
                    <a class="list-row list-row--interactive" [routerLink]="['/projects', task.projectId]" [queryParams]="{ task: task.id }">
                      <span class="mono text-tertiary">{{ task.key }}</span>
                      <span class="list-row__body"><span class="list-row__title">{{ task.title }}</span></span>
                      <span class="list-row__aside">{{ task.dueDate ? (task.dueDate | relativeTime) : '' }}</span>
                      <ui-badge [presentation]="taskStatus[task.status]" />
                    </a>
                  </li>
                } @empty {
                  <ui-empty-state [compact]="true" icon="check" heading="Nothing assigned to you" />
                }
              </ul>
            </ui-panel>
          </div>
          <ui-panel heading="Organizations">
            <ul class="orgs">
              @for (organization of workspace.organizations(); track organization.id) {
                <li class="orgs__item">
                  <span>
                    <span class="orgs__name">{{ organization.name }}</span>
                    <span class="orgs__meta">{{ organization.industry }}</span>
                  </span>
                  @if (organization.id === workspace.currentOrganizationId()) {
                    <span class="chip chip--accent">Current</span>
                  } @else {
                    <button type="button" class="btn btn--secondary btn--sm" (click)="organizationSwitch.switchTo(organization.id)">Switch</button>
                  }
                </li>
              }
            </ul>
          </ui-panel>
        </div>
      }
    </div>
  `,
  styles: `
    .identity {
      display: flex;
      align-items: center;
      gap: var(--space-4);
      margin-bottom: var(--space-6);
    }
    .identity__name {
      font-size: var(--text-xl);
      font-weight: var(--weight-semibold);
    }
    .identity__title {
      color: var(--color-text-secondary);
    }
    .identity__email {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
    .actions {
      align-items: flex-end;
    }
    .orgs {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .orgs__item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .orgs__item > span {
      display: flex;
      flex-direction: column;
    }
    .orgs__name {
      font-weight: var(--weight-medium);
    }
    .orgs__meta {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
    }
  `,
})
export class ProfilePage {
  protected readonly workspace = inject(WorkspaceStore);
  protected readonly organizationSwitch = inject(OrganizationSwitchService);
  private readonly projects = inject(ProjectStore);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly toast = inject(ToastService);

  protected readonly taskStatus = TASK_STATUS;
  protected readonly saving = signal(false);
  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, notBlank, Validators.maxLength(80)]],
    title: ['', Validators.maxLength(80)],
  });

  protected readonly myTasks = computed(() => {
    const me = this.workspace.currentMemberRef();
    return me ? this.projects.tasksForMember(me).filter((t) => t.status !== 'done') : [];
  });

  constructor() {
    effect(() => {
      const user = this.workspace.user();
      if (user) {
        untracked(() => this.form.reset({ displayName: user.displayName, title: user.title }));
      }
    });
  }

  protected error(name: keyof typeof this.form.controls, label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    try {
      const value = this.form.getRawValue();
      await this.workspace.updateProfile({ displayName: value.displayName.trim(), title: value.title.trim() });
      this.toast.success('Profile updated', 'Saved in the local mock workspace.');
    } catch (error) {
      this.toast.error('Could not update your profile', error);
    } finally {
      this.saving.set(false);
    }
  }
}
