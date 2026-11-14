import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AgentsApi } from '../../core/agents/agents.api';
import { ProviderConnection, ProviderType } from '../../core/agents/agents.models';
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
import { CONNECTION_STATUS_LABEL, CONNECTION_STATUS_TONE, PROVIDER_LABEL } from './ai-agents-format';

/** Printable ASCII without spaces, 8 to 512 characters: the shape of every provider API key. */
const CREDENTIAL = /^[!-~]{8,512}$/;

/**
 * Provider connections of the organization. The credential field is write-only: it is typed
 * once, sent to the Core API through the desktop proxy and cleared immediately. It is never
 * displayed again, stored in a store, or returned by the API.
 */
@Component({
  selector: 'app-providers-tab',
  imports: [ReactiveFormsModule, Panel, Badge, EmptyState, Skeleton, Icon, Modal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!store.can('provider.read')) {
      <ui-empty-state icon="lock" heading="No access" description="Your role does not allow viewing provider connections." />
    } @else {
      <ui-panel heading="Model providers" subheading="Connections owned by this organization. Credentials are never shown.">
        <ng-container panelActions>
          @if (store.can('provider.connect')) {
            <button type="button" class="btn btn--primary btn--sm" (click)="openCreate()"><ui-icon name="plus" [size]="14" /> Connect a provider</button>
          }
        </ng-container>
        @if (store.connectionsError(); as message) {
          <ui-empty-state icon="circle-alert" tone="danger" heading="Providers unavailable" [description]="message" [compact]="true" />
        } @else if (store.connectionsLoading() && store.connections().length === 0) {
          <ui-skeleton [rows]="3" />
        } @else if (store.connections().length === 0) {
          <ui-empty-state icon="shield-check" heading="No provider connected" [compact]="true"
            description="Connect Anthropic, OpenAI or Google Gemini so agents can be bound to a model." />
        } @else {
          <ul class="list">
            @for (connection of store.connections(); track connection.id) {
              <li class="row">
                <div class="row__main">
                  <strong>{{ connection.displayName }}</strong>
                  <span class="text-tertiary">{{ providerLabel[connection.providerType] }}@if (connection.accountIdentifier) { · {{ connection.accountIdentifier }} }</span>
                  @if (connection.statusDetail) { <span class="text-tertiary">{{ connection.statusDetail }}</span> }
                </div>
                <ui-badge [label]="statusLabel[connection.status]" [tone]="statusTone[connection.status]" />
                @if (connection.status !== 'REVOKED') {
                  <div class="row__actions">
                    @if (store.can('provider.test')) {
                      <button type="button" class="btn btn--secondary btn--sm" [disabled]="busy() === connection.id" (click)="test(connection)">
                        {{ busy() === connection.id ? 'Validating…' : 'Validate' }}
                      </button>
                    }
                    @if (store.can('provider.manage')) {
                      <button type="button" class="btn btn--secondary btn--sm" (click)="openReplace(connection)">Replace credential</button>
                      <button type="button" class="btn btn--ghost btn--sm danger" (click)="revoke(connection)">Revoke</button>
                    }
                  </div>
                }
              </li>
            }
          </ul>
        }
        <p class="text-tertiary note">
          Saving a credential does not make it valid: use Validate. Validation lists models and never runs a billable completion.
        </p>
      </ui-panel>
    }

    <ui-modal [heading]="target() ? 'Replace credential' : 'Connect a provider'"
      [description]="target() ? 'The previous credential is discarded. It is never displayed.' : 'The credential is encrypted by the server and can never be read back.'"
      [open]="creating() || target() !== null" [locked]="saving()" (closed)="close()">
      <form id="provider-form" class="form-grid" [formGroup]="form" (ngSubmit)="submit()" novalidate autocomplete="off">
        @if (!target()) {
          <div class="field">
            <label class="field__label" for="pc-type">Provider</label>
            <select id="pc-type" class="select" formControlName="providerType">
              @for (type of types; track type) { <option [value]="type">{{ providerLabel[type] }}</option> }
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="pc-name">Display name<span class="field__required" aria-hidden="true">*</span></label>
            <input id="pc-name" class="input" formControlName="displayName" maxlength="80" autocomplete="off" />
            @if (error('displayName', 'Display name'); as message) { <span class="field__error">{{ message }}</span> }
          </div>
          <div class="field field--full">
            <label class="field__label" for="pc-account">Account identifier</label>
            <input id="pc-account" class="input" formControlName="accountIdentifier" maxlength="120" autocomplete="off" />
            <span class="field__hint">Optional label (project or account name). Never put a secret here.</span>
          </div>
        }
        <div class="field field--full">
          <label class="field__label" for="pc-credential">API key<span class="field__required" aria-hidden="true">*</span></label>
          <input id="pc-credential" class="input" type="password" formControlName="credential" autocomplete="new-password"
            autocapitalize="off" autocorrect="off" spellcheck="false" inputmode="text"
            data-1p-ignore="true" data-lpignore="true" data-bwignore="true" data-form-type="other" />
          @if (error('credential', 'API key'); as message) { <span class="field__error">{{ message }}</span> }
        </div>
      </form>
      <ng-container modalFooter>
        <button type="button" class="btn btn--secondary" [disabled]="saving()" (click)="close()">Cancel</button>
        <button type="submit" form="provider-form" class="btn btn--primary" [disabled]="saving()">
          {{ target() ? 'Replace credential' : 'Connect' }}
        </button>
      </ng-container>
    </ui-modal>
  `,
  styles: `
    .list { list-style: none; margin: 0; padding: 0; }
    .row { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) 0; border-bottom: 1px solid var(--color-border); flex-wrap: wrap; }
    .row__main { display: flex; flex-direction: column; flex: 1 1 240px; min-width: 0; }
    .row__actions { display: flex; gap: var(--space-1); flex-wrap: wrap; }
    .note { margin-top: var(--space-3); font-size: var(--text-sm); }
    .danger { color: var(--color-danger); }
  `,
})
export class ProvidersTab {
  protected readonly store = inject(AgentsStore);
  private readonly api = inject(AgentsApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly providerLabel = PROVIDER_LABEL;
  protected readonly statusLabel = CONNECTION_STATUS_LABEL;
  protected readonly statusTone = CONNECTION_STATUS_TONE;
  protected readonly types: readonly ProviderType[] = ['ANTHROPIC', 'OPENAI', 'GEMINI'];

  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly creating = signal(false);
  protected readonly target = signal<ProviderConnection | null>(null);
  protected readonly saving = signal(false);
  protected readonly busy = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    providerType: ['ANTHROPIC' as ProviderType],
    displayName: ['', [Validators.required, notBlank, Validators.minLength(2), Validators.maxLength(80)]],
    accountIdentifier: ['', Validators.maxLength(120)],
    credential: ['', [Validators.required, Validators.pattern(CREDENTIAL)]],
  });

  constructor() {
    // The typed key does not outlive the moment it is needed: it is wiped when the window loses focus,
    // when the page is hidden and when the component is destroyed (it is also wiped on close and submit).
    const wipe = () => {
      this.form.controls.credential.reset('');
      // Also clear the DOM node itself: it can outlive the view (detached, awaiting garbage collection).
      const input = this.host.nativeElement.querySelector<HTMLInputElement>('#pc-credential');
      if (input) {
        input.value = '';
      }
    };
    const hidden = () => {
      if (document.visibilityState === 'hidden') {
        wipe();
      }
    };
    window.addEventListener('blur', wipe);
    document.addEventListener('visibilitychange', hidden);
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('blur', wipe);
      document.removeEventListener('visibilitychange', hidden);
      wipe();
    });
  }

  protected openCreate(): void {
    this.form.reset({ providerType: 'ANTHROPIC', displayName: '', accountIdentifier: '', credential: '' });
    this.form.controls.displayName.enable();
    this.creating.set(true);
  }

  protected openReplace(connection: ProviderConnection): void {
    this.form.reset({ providerType: connection.providerType, displayName: connection.displayName, accountIdentifier: '', credential: '' });
    this.target.set(connection);
  }

  protected close(): void {
    // The credential never outlives the dialog.
    this.form.controls.credential.reset('');
    this.creating.set(false);
    this.target.set(null);
  }

  protected error(name: 'displayName' | 'credential', label: string): string | null {
    return errorMessage(this.form.controls[name], label);
  }

  protected async submit(): Promise<void> {
    const replacing = this.target();
    const { credential, displayName } = this.form.controls;
    // When replacing, the (hidden) display name is irrelevant.
    const relevant = replacing ? [credential] : [credential, displayName];
    relevant.forEach((control) => control.markAsTouched());
    if (relevant.some((control) => control.invalid) || this.saving()) {
      return;
    }
    // Move the key out of the form immediately: only this local variable holds it during the request.
    const { credential: secret, ...value } = this.form.getRawValue();
    credential.reset('');
    this.saving.set(true);
    try {
      const saved = await this.store.mutate((organizationId) =>
        replacing
          ? this.api.replaceCredential(organizationId, replacing.id, secret)
          : this.api.createConnection(organizationId, {
              providerType: value.providerType,
              displayName: value.displayName.trim(),
              ...(value.accountIdentifier.trim() ? { accountIdentifier: value.accountIdentifier.trim() } : {}),
              credential: secret,
            }),
      );
      this.toast.success(replacing ? 'Credential replaced' : 'Provider connected', `${saved.displayName} is not validated yet.`);
      this.close();
    } catch (error) {
      this.toast.error(replacing ? 'Could not replace the credential' : 'Could not connect the provider', error);
    } finally {
      this.form.controls.credential.reset('');
      this.saving.set(false);
    }
  }

  protected async test(connection: ProviderConnection): Promise<void> {
    this.busy.set(connection.id);
    try {
      const result = await this.store.mutate((organizationId) => this.api.testConnection(organizationId, connection.id));
      if (result.outcome === 'VALID') {
        this.toast.success('Credential valid', result.message);
      } else {
        this.toast.error('Validation did not succeed', result.message);
      }
    } catch (error) {
      this.toast.error('Could not validate the connection', error);
    } finally {
      this.busy.set(null);
    }
  }

  protected async revoke(connection: ProviderConnection): Promise<void> {
    const answer = await this.confirm.ask({
      title: `Revoke ${connection.displayName}?`,
      message: 'The stored credential is destroyed and the connection can no longer be used. This cannot be undone.',
      confirmLabel: 'Revoke',
      tone: 'danger',
    });
    if (!answer.confirmed) {
      return;
    }
    try {
      await this.store.mutate((organizationId) => this.api.revokeConnection(organizationId, connection.id));
      this.toast.success('Connection revoked');
    } catch (error) {
      this.toast.error('Could not revoke the connection', error);
    }
  }
}
