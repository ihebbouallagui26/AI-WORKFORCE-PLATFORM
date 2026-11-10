import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/components/empty-state/empty-state';

@Component({
  selector: 'app-not-found-page',
  imports: [EmptyState, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <ui-empty-state icon="help" heading="Page not found" description="The page you are looking for does not exist or has moved.">
        <a class="btn btn--primary" routerLink="/dashboard">Back to overview</a>
      </ui-empty-state>
    </div>
  `,
})
export class NotFoundPage {}
