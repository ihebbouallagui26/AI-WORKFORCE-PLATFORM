import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { AgentStore } from '../../core/state/agent.store';
import { DirectoryStore } from '../../core/state/directory.store';
import { ProjectStore } from '../../core/state/project.store';
import { Icon } from '../../shared/components/icon/icon';
import { IconName } from '../../shared/components/icon/icon-registry';

interface SearchResult {
  readonly id: string;
  readonly group: 'Agents' | 'People' | 'Projects' | 'Tasks';
  readonly icon: IconName;
  readonly title: string;
  readonly meta: string;
  readonly commands: readonly unknown[];
  readonly queryParams?: Record<string, string>;
}

const MAX_PER_GROUP = 4;

/** Workspace-wide search across agents, people, projects and tasks (Ctrl+K). */
@Component({
  selector: 'app-global-search',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onDocumentKeydown($event)' },
  templateUrl: './global-search.html',
  styleUrl: './global-search.scss',
})
export class GlobalSearch {
  private readonly router = inject(Router);
  private readonly agents = inject(AgentStore);
  private readonly directory = inject(DirectoryStore);
  private readonly projects = inject(ProjectStore);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  protected readonly query = signal('');
  protected readonly open = signal(false);
  protected readonly activeIndex = signal(0);

  protected readonly results = computed<readonly SearchResult[]>(() => {
    const q = this.query().trim().toLowerCase();
    if (q.length < 2) {
      return [];
    }
    const matches = (...values: readonly string[]) => values.some((v) => v.toLowerCase().includes(q));

    const agentResults = this.agents
      .agents()
      .map((agent) => ({ agent, profile: this.directory.resolve({ kind: 'agent', id: agent.id }) }))
      .filter(({ agent, profile }) =>
        matches(agent.identity.fullName, profile.roleTitle, profile.departmentName, ...agent.identity.skills),
      )
      .slice(0, MAX_PER_GROUP)
      .map<SearchResult>(({ agent, profile }) => ({
        id: `agent-${agent.id}`,
        group: 'Agents',
        icon: 'agent',
        title: agent.identity.fullName,
        meta: `${profile.roleTitle} · ${profile.departmentName}`,
        commands: ['/agents', agent.id],
      }));

    const peopleResults = this.directory
      .members()
      .filter((m) => m.kind === 'human' && matches(m.name, m.roleTitle, m.departmentName))
      .slice(0, MAX_PER_GROUP)
      .map<SearchResult>((m) => ({
        id: `human-${m.ref.id}`,
        group: 'People',
        icon: 'user',
        title: m.name,
        meta: `${m.roleTitle} · ${m.departmentName}`,
        commands: ['/organization'],
        queryParams: { member: m.ref.id },
      }));

    const projectResults = this.projects
      .projects()
      .filter((p) => matches(p.name, p.code, p.description))
      .slice(0, MAX_PER_GROUP)
      .map<SearchResult>((p) => ({
        id: `project-${p.id}`,
        group: 'Projects',
        icon: 'projects',
        title: p.name,
        meta: p.code,
        commands: ['/projects', p.id],
      }));

    const taskResults = this.projects
      .tasks()
      .filter((t) => matches(t.key, t.title))
      .slice(0, MAX_PER_GROUP)
      .map<SearchResult>((t) => ({
        id: `task-${t.id}`,
        group: 'Tasks',
        icon: 'check',
        title: t.title,
        meta: `${t.key} · ${this.projects.projectById().get(t.projectId)?.name ?? ''}`,
        commands: ['/projects', t.projectId],
        queryParams: { task: t.id },
      }));

    return [...agentResults, ...peopleResults, ...projectResults, ...taskResults];
  });

  protected readonly showPanel = computed(() => this.open() && this.query().trim().length >= 2);

  protected onInput(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
    this.open.set(true);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.results().length;
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (count) this.activeIndex.update((i) => (i + 1) % count);
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (count) this.activeIndex.update((i) => (i - 1 + count) % count);
        break;
      case 'Enter': {
        const result = this.results()[this.activeIndex()];
        if (result) {
          event.preventDefault();
          this.select(result);
        }
        break;
      }
      case 'Escape':
        if (this.open() && this.query()) {
          event.stopPropagation();
          this.open.set(false);
        } else {
          this.field().nativeElement.blur();
        }
        break;
    }
  }

  protected select(result: SearchResult): void {
    void this.router.navigate(result.commands, { queryParams: result.queryParams });
    this.query.set('');
    this.open.set(false);
    this.field().nativeElement.blur();
  }

  protected isFirstOfGroup(index: number): boolean {
    const results = this.results();
    return index === 0 || results[index - 1].group !== results[index].group;
  }

  protected onFocusOut(event: FocusEvent): void {
    if (!this.host.nativeElement.contains(event.relatedTarget as Node | null)) {
      this.open.set(false);
    }
  }

  protected onDocumentKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      const input = this.field().nativeElement;
      input.focus();
      input.select();
      this.open.set(true);
    }
  }
}
