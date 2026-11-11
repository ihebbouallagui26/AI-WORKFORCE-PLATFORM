import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Id } from '../../../core/models';
import { DirectoryStore } from '../../../core/state/directory.store';
import { DepartmentNode } from '../../../core/state/organization.store';
import { Member } from '../../../shared/components/member/member';

/** Hierarchical view of departments, rendered recursively as an indented tree of cards. */
@Component({
  selector: 'app-org-chart',
  imports: [Member, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-template #branch let-nodes>
      <ul class="chart__level">
        @for (node of nodes; track node.department.id) {
          <li class="chart__node">
            <button type="button" class="chart__card" (click)="selected.emit(node.department.id)">
              <span class="chart__head">
                <span class="chart__code">{{ node.department.code }}</span>
                <span class="chart__name">{{ node.department.name }}</span>
              </span>
              @if (node.department.managerRef) {
                <ui-member [ref]="node.department.managerRef" size="xs" [showRole]="false" />
              } @else {
                <span class="text-tertiary">No manager</span>
              }
              <span class="chart__counts tabular">
                {{ countLabel(counts(node.department.id).humans, 'person', 'people') }} ·
                {{ countLabel(counts(node.department.id).agents, 'AI agent', 'AI agents') }}
              </span>
            </button>
            @if (node.children.length) {
              <ng-container *ngTemplateOutlet="branch; context: { $implicit: node.children }" />
            }
          </li>
        }
      </ul>
    </ng-template>
    <ng-container *ngTemplateOutlet="branch; context: { $implicit: tree() }" />
  `,
  styles: `
    .chart__level {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .chart__level .chart__level {
      position: relative;
      margin: var(--space-2) 0 0 var(--space-6);
      padding-left: var(--space-4);
      border-left: 1px solid var(--color-border);
    }
    .chart__node {
      position: relative;
    }
    .chart__level .chart__level > .chart__node::before {
      content: '';
      position: absolute;
      left: calc(var(--space-4) * -1);
      top: 26px;
      width: var(--space-4);
      height: 1px;
      background: var(--color-border);
    }
    .chart__card {
      display: grid;
      grid-template-columns: minmax(180px, 1.2fr) minmax(160px, 1fr) auto;
      align-items: center;
      gap: var(--space-4);
      width: 100%;
      padding: var(--space-3) var(--space-4);
      border: 1px solid var(--color-border-subtle);
      border-radius: var(--radius-lg);
      background: var(--color-surface-1);
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
      transition: border-color var(--duration-fast) var(--ease-standard);
    }
    .chart__card:hover {
      border-color: var(--color-border-strong);
      background: var(--color-surface-2);
    }
    .chart__head {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      min-width: 0;
    }
    .chart__code {
      padding: 1px 6px;
      border-radius: var(--radius-sm);
      background: var(--color-surface-3);
      color: var(--color-text-secondary);
      font-size: var(--text-2xs);
      font-weight: var(--weight-semibold);
      letter-spacing: 0.04em;
    }
    .chart__name {
      color: var(--color-text-primary);
      font-weight: var(--weight-semibold);
    }
    .chart__counts {
      color: var(--color-text-tertiary);
      font-size: var(--text-xs);
      white-space: nowrap;
    }
  `,
})
export class OrgChart {
  readonly tree = input.required<readonly DepartmentNode[]>();
  readonly selected = output<Id>();

  private readonly directory = inject(DirectoryStore);

  protected countLabel(count: number, singular: string, plural: string): string {
    return `${count} ${count === 1 ? singular : plural}`;
  }

  protected counts(departmentId: Id): { humans: number; agents: number } {
    const members = this.directory.membersOfDepartment(departmentId);
    return {
      humans: members.filter((m) => m.kind === 'human').length,
      agents: members.filter((m) => m.kind === 'agent').length,
    };
  }
}
