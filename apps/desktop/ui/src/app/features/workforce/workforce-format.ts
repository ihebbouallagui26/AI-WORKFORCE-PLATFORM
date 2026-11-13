import { ArchiveStatus, EmploymentStatus } from '../../core/workforce/workforce.models';
import { Tone } from '../../shared/utils/presentation';

export const EMPLOYMENT_LABEL: Readonly<Record<EmploymentStatus, string>> = {
  ACTIVE: 'Active',
  ON_LEAVE: 'On leave',
  INACTIVE: 'Inactive',
};

export const EMPLOYMENT_TONE: Readonly<Record<EmploymentStatus, Tone>> = {
  ACTIVE: 'success',
  ON_LEAVE: 'warning',
  INACTIVE: 'neutral',
};

export const ARCHIVE_LABEL: Readonly<Record<ArchiveStatus, string>> = {
  ACTIVE: 'Active',
  ARCHIVED: 'Archived',
};

export const ARCHIVE_TONE: Readonly<Record<ArchiveStatus, Tone>> = {
  ACTIVE: 'success',
  ARCHIVED: 'neutral',
};

/** Value of a form select as an optional id (empty string means "none"). */
export function idOrNull(value: string | null | undefined): string | null {
  return value ? value : null;
}
