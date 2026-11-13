import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { SessionService } from '../../core/auth/session.service';
import { ApiError } from '../../core/identity/backend-client';
import { IdentityApi } from '../../core/identity/identity.api';
import { ToastService } from '../../core/services/toast.service';
import { WorkspaceStore } from '../../core/state/workspace.store';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Panel } from '../../shared/components/panel/panel';

/**
 * Accepts an invitation with the code received by e-mail. The API checks that the code is
 * valid, unused and unexpired, and that the signed-in account's verified e-mail matches.
 */
@Component({
  selector: 'app-accept-invitation-page',
  imports: [ReactiveFormsModule, PageHeader, Panel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-page-header heading="Accept an invitation" description="Paste the invitation code you received by e-mail." />
      <ui-panel heading="Invitation code" [subheading]="'Signed in as ' + (session.user()?.email ?? '')">
        <form class="stack" [formGroup]="form" (ngSubmit)="accept()" novalidate>
          <div class="field">
            <label class="field__label" for="invitation-code">Code</label>
            <input id="invitation-code" class="input mono" formControlName="token" autocomplete="off" spellcheck="false" maxlength="64" />
            @if (form.controls.token.touched && form.controls.token.invalid) {
              <span class="field__error">The code has 43 characters (letters, digits, - and _).</span>
            }
            @if (failure(); as message) {
              <span class="field__error" role="alert">{{ message }}</span>
            }
          </div>
          @if (session.user() && !session.user()!.emailVerified) {
            <p class="text-tertiary">Your e-mail address is not verified yet: verify it from the sign-in e-mail before accepting.</p>
          }
          <div class="actions">
            <button type="submit" class="btn btn--primary" [disabled]="busy()">Join organization</button>
          </div>
        </form>
      </ui-panel>
    </div>
  `,
  styles: `
    .mono { font-family: var(--font-mono); }
    .actions { display: flex; justify-content: flex-end; }
  `,
})
export class AcceptInvitationPage {
  protected readonly session = inject(SessionService);
  private readonly api = inject(IdentityApi);
  private readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    token: ['', [Validators.required, Validators.pattern(/^\s*[A-Za-z0-9_-]{43}\s*$/)]],
  });

  protected async accept(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    try {
      const result = await this.api.acceptInvitation(this.form.getRawValue().token.trim());
      this.form.reset();
      this.workspace.switchOrganization(result.organizationId);
      this.workspace.reload();
      this.toast.success('Invitation accepted', `You joined ${result.organizationName}.`);
      void this.router.navigateByUrl('/dashboard');
    } catch (error) {
      this.failure.set(error instanceof ApiError && error.status === 403
        ? 'This invitation was sent to a different e-mail address.'
        : error instanceof ApiError && error.status === 404
          ? 'Unknown invitation code.'
          : error instanceof Error ? error.message : 'The invitation could not be accepted.');
    } finally {
      this.busy.set(false);
    }
  }
}
