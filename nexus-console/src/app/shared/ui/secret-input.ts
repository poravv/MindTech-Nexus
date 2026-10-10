import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { Icon } from './icon';

/** Password-like input with a show/hide toggle. Bind it with ngModel; `inputId` pairs it with its <label>. */
@Component({
  selector: 'app-secret-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SecretInput), multi: true }],
  template: `
    <div class="input-affix">
      <input [id]="inputId()" class="input mono" [type]="shown() ? 'text' : 'password'" [value]="value()"
             [attr.autocomplete]="autocomplete()" [attr.placeholder]="placeholder() || null" [disabled]="disabled()"
             [attr.aria-describedby]="describedBy() || null" [attr.aria-invalid]="invalid() || null"
             spellcheck="false" (input)="write($any($event.target).value)" (blur)="touched()" />
      <button type="button" class="btn btn-ghost btn-sm btn-icon affix-button" (click)="shown.set(!shown())"
              [attr.aria-label]="shown() ? 'Ocultar' : 'Mostrar'" [attr.aria-pressed]="shown()">
        <app-icon [name]="shown() ? 'eye-off' : 'eye'" />
      </button>
    </div>
  `,
})
export class SecretInput implements ControlValueAccessor {
  readonly inputId = input.required<string>();
  readonly autocomplete = input('off');
  readonly placeholder = input('');
  readonly describedBy = input('');
  readonly invalid = input(false);

  protected readonly shown = signal(false);
  protected readonly value = signal('');
  protected readonly disabled = signal(false);
  private onChange: (value: string) => void = () => {};
  protected touched: () => void = () => {};

  writeValue(value: string | null): void {
    this.value.set(value ?? '');
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.touched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  protected write(value: string): void {
    this.value.set(value);
    this.onChange(value);
  }
}
