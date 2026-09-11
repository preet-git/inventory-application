import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, Observable, throwError } from 'rxjs';
import { API_URL } from './api-url';
import type {
  ApiError,
  ImportResult,
  InventorySummary,
  ProductPage,
  SortDirection,
} from './models';

/** An API error that already carries a message worth showing a person. */
export class InventoryApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'InventoryApiError';
  }
}

/**
 * Every call the dashboard makes.
 *
 * <p>The one piece of translation it does is on failure: the backend answers every error with the
 * same `{code, message}` body, and that message is written for a person, so it is unwrapped here
 * and thrown as an {@link InventoryApiError}. Components then never touch `HttpErrorResponse`.
 */
@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly http = inject(HttpClient);

  /** The whole inventory, for the summary card. */
  summary(): Observable<InventorySummary> {
    return this.http
      .get<InventorySummary>(`${API_URL}/products/summary`)
      .pipe(catchError(toApiError));
  }

  /**
   * One page of the table.
   *
   * @param cursor the previous page's `nextCursor`, or null for the first page. A cursor belongs
   *               to the sort it was issued under, so changing `sortBy` or `direction` means
   *               starting again from null.
   */
  products(options: {
    cursor: string | null;
    size: number;
    sortBy: string;
    direction: SortDirection;
  }): Observable<ProductPage> {
    const params: Record<string, string> = {
      size: String(options.size),
      sortBy: options.sortBy,
      direction: options.direction,
    };
    if (options.cursor) {
      params['cursor'] = options.cursor;
    }
    return this.http
      .get<ProductPage>(`${API_URL}/products`, { params })
      .pipe(catchError(toApiError));
  }

  /**
   * Hands the file over. Returns as soon as it is accepted -- the import itself runs on the
   * server, so the caller polls {@link importStatus} for the outcome.
   */
  startImport(file: File): Observable<ImportResult> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http
      .post<ImportResult>(`${API_URL}/imports`, body)
      .pipe(catchError(toApiError));
  }

  /** Where an import has got to. The counters climb between calls while it runs. */
  importStatus(importId: number): Observable<ImportResult> {
    return this.http
      .get<ImportResult>(`${API_URL}/imports/${importId}`)
      .pipe(catchError(toApiError));
  }
}

/**
 * Turns a transport failure into something with a message.
 *
 * <p>Status 0 is the case worth naming: the request never reached the server, which in practice
 * means the backend is not running or the browser blocked it, and "Http failure response for
 * ...: 0 Unknown Error" tells nobody that.
 */
function toApiError(response: HttpErrorResponse) {
  if (response.status === 0) {
    return throwError(
      () =>
        new InventoryApiError(
          'network_error',
          'Cannot reach the API. Check that the backend is running and reachable.',
          0,
        ),
    );
  }
  const body = response.error as ApiError | null;
  const code = body?.code ?? 'unexpected_error';
  const message = body?.message ?? `The API returned ${response.status}.`;
  return throwError(() => new InventoryApiError(code, message, response.status));
}
