import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Requires an array value with at least `min` items. */
export function minItems(min: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    const length = Array.isArray(value) ? value.length : 0;
    return length >= min ? null : { minItems: { min, actual: length } };
  };
}

/** Rejects strings made only of whitespace. */
export const notBlank: ValidatorFn = (control) =>
  typeof control.value === 'string' && control.value.length > 0 && control.value.trim().length === 0
    ? { blank: true }
    : null;

/** Human-readable message for the first error of a control. */
export function errorMessage(control: AbstractControl | null, label: string): string | null {
  if (!control || !control.errors || !(control.touched || control.dirty)) {
    return null;
  }
  const errors = control.errors;
  if (errors['required'] || errors['blank']) return `${label} is required.`;
  if (errors['minlength']) return `${label} must be at least ${errors['minlength'].requiredLength} characters.`;
  if (errors['maxlength']) return `${label} must be at most ${errors['maxlength'].requiredLength} characters.`;
  if (errors['min']) return `${label} must be at least ${errors['min'].min}.`;
  if (errors['max']) return `${label} must be at most ${errors['max'].max}.`;
  if (errors['minItems']) return `Add at least ${errors['minItems'].min} item.`;
  if (errors['pattern']) return `${label} has an invalid format.`;
  return `${label} is invalid.`;
}
