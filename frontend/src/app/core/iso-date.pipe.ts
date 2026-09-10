import { Pipe, PipeTransform } from '@angular/core';

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/**
 * Formats the API's `YYYY-MM-DD` strings without going through `Date`.
 *
 * <p>`DatePipe` would be the obvious choice and is the wrong one here: it parses a date-only ISO
 * string as UTC midnight and then renders it in the browser's timezone, so a purchase date of
 * 2024-03-16 displays as 15 Mar to anyone west of Greenwich. A purchase date has no time and no
 * zone, so it is formatted as the text it already is.
 */
@Pipe({ name: 'isoDate' })
export class IsoDatePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
    if (!match) {
      return value;
    }
    const [, year, month, day] = match;
    return `${day} ${MONTHS[Number(month) - 1]} ${year}`;
  }
}
