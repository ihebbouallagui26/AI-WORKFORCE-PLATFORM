import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AgentsApi } from '../../core/agents/agents.api';
import { Skill } from '../../core/agents/agents.models';
import { AgentsStore } from '../../core/agents/agents.store';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { Badge } from '../../shared/components/badge/badge';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Modal } from '../../shared/components/modal/modal';
import { Panel } from '../../shared/components/panel/panel';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { errorMessage, notBlank } from '../../shared/utils/validators';

/** The organization's skill catalog (assigned to agents from the agent page). */
@Component({
  selector: 'app-skills-tab',
  imports: [ReactiveFormsModule, Panel, Badge, EmptyState, Skeleton, Icon, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('skill.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing skills." />
    } @else {
      <ui-panel heading="Skills" subheading="Capabilities that can be assigned to agents">
        <ng-container panelActions>
          @if (store.can('skill.manage')) {
            <button type="button" class="btn btn--primary btn--sm" (click)="openCreate()"><ui-icon name="plus" [size]="14" /> New skill</button>
          }
        </ng-container>
        @if (store.skillsError(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Skills unavailable" [description]="message" [compact]="true" />
        } @else if (store.skillsLoading() && store.skills().length === 0) {
          <ui-skeleton [rows]="3" />
        } @else if (store.skills().length === 0) {
          <ui-empty-state icon="inbox" heading="No skill yet" [compact]="true" description="Create skills such as Code review or Market research." />
        } @else {
          <ul class="list">
            @for (skill of store.skills(); track skill.id) {
              <li class="row">
                <div class="row__main">
                  <strong>{{ skill.name }}</strong>
                  <span class="text-tertiary">{{ skill.category }}@if (skill.description) { · {{ skill.description }} }</span>
                </div>
                <ui-badge [label]="skill.active ? 'Active' : 'Inactive'" [tone]="skill.active ? 'success' : 'neutral'" />
                @if (store.can('skill.manage')) {
                  <button type="button" class="btn btn--secondary btn--sm" (click)="toggle(skill)">{{ skill.active ? 'Deactivate' : 'Activate' }}</button>
                  <button type="button" class="btn btn--ghost btn--sm danger" (click)="remove(skill)">Delete</button>
                }
              </li>
            }
          </ul>
        }
      </ui-panel>
    }

    <ui-modal heading="New skill" [open]="creating()" [locked]="saving()" (closed)="creating.set(false)">
      <form id="skill-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label class="field__label" for="sk-name">Name<span class="field__required" aria-hidden="true">*</span></label>
          <input id="sk-name" class="input" formControlName="name" maxlength="80" autocomplete="off" />
          @if (error('name', 'Name'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
        <div class="field">
          <label class="field__label" for="sk-category">Category</label>
          <input id="sk-category" class="input" formControlName="category" maxlength="40" autocomplete="off" />
        </div>
        <div class="field field--full">
          <label class="field__label" for="sk-description">Description</label>
          <textarea id="sk-description" class="textarea" rows="3" maxlength="500" formControlName="description"></textarea>
        </div>
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="creating.set(false)">Cancel</button>
        <button type="submit" form="skill-form" class="btn btn--primary" [disabled]="saving()">Create skill</button>
      </ng-container>
    </ui-modal>
  `,
  styles: `
    .list { list-style: none; margin: 0; padding: 0; }
    .row { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) 0; border-bottom: 1px solid var(--color-border); }
    .row__main { display: flex; flex-direction: column; flex: 1; min-width: 0; }
    .danger { color: var(--color-danger); }
  `,
})
export class SkillsTab {
  protected readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(80)]],
    category: ['', Validators.maxLength(40)],
    description: ['', Validators.maxLength(500)],
  });

  protected openCreate(): void {
    this.form.reset({ name: '', category: '', description: '' });
    this.creating.set(true);
  }

  protected error(name: 'name', label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    try {
      const skill = await this.store.mutate((id) =>
        this.api.createSkill(id, {
          name: value.name.trim(),
          ...(value.category.trim() ? { category: value.category.trim() } : {}),
          description: value.description.trim(),
        }),
      );
      this.toast.success('Skill created', skill.name);
      this.creating.set(false);
    } catch (error) {
      this.toast.error('Could not create the skill', error);
    } finally {
      this.saving.set(false);
    }
  }

  protected async toggle(skill: Skill): Promise<void> {
    try {
      await this.store.mutate((id) => this.api.updateSkill(id, skill.id, { active: !skill.active }));
    } catch (error) {
      this.toast.error('Could not update the skill', error);
    }
  }

  protected async remove(skill: Skill): Promise<void> {
    const answer = await this.confirm.ask({
      title: `Delete ${skill.name}?`,
      message: 'It is removed from every agent that uses it.',
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!answer.confirmed) {
      return;
    }
    try {
      await this.store.mutate((id) => this.api.deleteSkill(id, skill.id));
      this.toast.success('Skill deleted');
    } catch (error) {
      this.toast.error('Could not delete the skill', error);
    }
  }
}
