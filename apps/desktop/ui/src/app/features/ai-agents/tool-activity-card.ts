import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { ToolDefinition, ToolInvocation } from '../../core/agents/agents.models';
import { Badge } from '../../shared/components/badge/badge';
import {
  INVOCATION_LABEL,
  INVOCATION_TONE,
  MAX_ARGUMENT_CHARS,
  MAX_RESULT_CHARS,
  POLICY_LABEL,
  bounded,
  durationMs,
  formatDuration,
  maskSensitive,
} from './execution-timeline.model';

let nextId = 0;

/**
 * One tool call of an execution. The collapsed card shows what happened; expanding it shows the safe details:
 * the server's redacted arguments and the bounded result, always as TEXT (never interpreted as HTML), masked once
 * more here if anything still looks like a credential.
 */
@Component({
  selector: 'app-tool-activity-card',
  imports: [Badge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="card" [attr.data-testid]="'tool-call-' + call().sequence">
      <button type="button" class="head" [attr.aria-expanded]="open()" [attr.aria-controls]="panelId" (click)="open.set(!open())">
        <span class="name">{{ name() }}</span>
        <ui-badge [label]="statusLabel()" [tone]="statusTone()" />
        <span class="text-tertiary">{{ policyLabel() }}</span>
        @if (duration(); as value) { <span class="text-tertiary">{{ value }}</span> }
        <span class="chevron" aria-hidden="true">{{ open() ? '▾' : '▸' }}</span>
      </button>
      @if (open()) {
        <div class="body" [id]="panelId">
          <dl>
            @if (tool(); as t) {
              <dt>Tool</dt><dd>{{ t.description }}</dd>
              @if (t.mcpServer) { <dt>MCP server</dt><dd>{{ t.mcpServer }}</dd> }
              <dt>Risk</dt><dd>{{ t.risk }}</dd>
            }
            <dt>Requested</dt><dd>{{ call().requestedAt }}</dd>
            @if (call().startedAt) { <dt>Started</dt><dd>{{ call().startedAt }}</dd> }
            @if (call().completedAt) { <dt>Completed</dt><dd>{{ call().completedAt }}</dd> }
            @if (call().reason) { <dt>Why the agent asked</dt><dd>{{ reason() }}</dd> }
          </dl>
          <h4>Input</h4>
          <pre class="text" data-testid="tool-input">{{ input().text }}</pre>
          @if (input().clipped) { <p class="text-tertiary">Only the beginning is shown.</p> }
          @if (call().result !== null) {
            <h4>Output</h4>
            <pre class="text" data-testid="tool-output">{{ output().text }}</pre>
            @if (call().resultTruncated) { <p class="text-tertiary" data-testid="tool-output-truncated">The platform limited this output before storing it.</p> }
            @if (output().clipped) { <p class="text-tertiary" data-testid="tool-output-clipped">Only the beginning is shown here.</p> }
          }
          @if (error(); as message) { <p class="error" role="alert" data-testid="tool-error">{{ message }}</p> }
        </div>
      }
    </article>
  `,
  styles: `
    .card { border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-1, var(--color-bg-app)); }
    .head { display: flex; align-items: center; gap: var(--space-2); width: 100%; padding: var(--space-2) var(--space-3); background: none; border: 0; color: inherit; font: inherit; text-align: left; cursor: pointer; flex-wrap: wrap; }
    .head:focus-visible { outline: 2px solid var(--color-accent); outline-offset: -2px; border-radius: var(--radius-md); }
    .name { font-weight: var(--weight-medium); overflow-wrap: anywhere; }
    .chevron { margin-left: auto; }
    .body { padding: 0 var(--space-3) var(--space-3); border-top: 1px solid var(--color-border); }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: var(--space-1) var(--space-3); margin: var(--space-2) 0; }
    dt { color: var(--color-text-tertiary); }
    dd { margin: 0; overflow-wrap: anywhere; }
    h4 { margin: var(--space-2) 0 var(--space-1); font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .04em; color: var(--color-text-tertiary); }
    .text { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 14rem; overflow: auto; margin: 0; padding: var(--space-2) var(--space-3); background: var(--color-surface-2); border-radius: var(--radius-md); font: var(--text-xs) ui-monospace, monospace; }
    .error { color: var(--color-danger); }
  `,
})
export class ToolActivityCard {
  readonly call = input.required<ToolInvocation>();
  /** Catalog entry (description, MCP server, risk) when the user may read the tool catalog. */
  readonly tool = input<ToolDefinition | null>(null);

  protected readonly open = signal(false);
  protected readonly panelId = `tool-call-panel-${nextId++}`;

  protected readonly name = computed(() => this.call().toolName ?? this.call().toolCode);
  protected readonly statusLabel = computed(() => INVOCATION_LABEL[this.call().status]);
  protected readonly statusTone = computed(() => INVOCATION_TONE[this.call().status]);
  protected readonly policyLabel = computed(() => POLICY_LABEL[this.call().policy]);
  protected readonly duration = computed(() => formatDuration(durationMs(this.call().startedAt, this.call().completedAt)));
  protected readonly input = computed(() => bounded(this.call().argumentsRedacted, MAX_ARGUMENT_CHARS));
  protected readonly output = computed(() => bounded(this.call().result, MAX_RESULT_CHARS));
  protected readonly reason = computed(() => maskSensitive(this.call().reason));
  protected readonly error = computed(() => {
    const { errorCode, errorMessage } = this.call();
    if (!errorCode && !errorMessage) {
      return null;
    }
    return maskSensitive(errorMessage ?? '').trim().slice(0, 300) || `Error ${errorCode}`;
  });
}
