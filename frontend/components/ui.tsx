import { cn } from '@/lib/utils';

/** The handful of primitives every dashboard page shares. Same classes as the auth pages. */

export const inputClass =
  'h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring';

export const buttonClass =
  'inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground disabled:opacity-60';

export const secondaryButtonClass =
  'inline-flex h-9 items-center justify-center rounded-md border border-input px-4 text-sm font-medium hover:bg-accent disabled:opacity-60';

export function PageHeader({ title, description, children }: {
  title: string;
  description?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-medium">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </div>
  );
}

export function Card({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className={cn('rounded-xl border border-border bg-card p-5', className)}>{children}</div>
  );
}

export function Notice({ kind, children }: { kind: 'error' | 'success'; children: React.ReactNode }) {
  return (
    <p
      className={cn(
        'rounded-lg px-4 py-3 text-sm',
        kind === 'error' ? 'bg-destructive/10 text-destructive' : 'bg-muted text-foreground',
      )}
    >
      {children}
    </p>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

export function DataGrid({ columns, rows }: { columns: string[]; rows: Record<string, unknown>[] }) {
  if (columns.length === 0) return <p className="text-sm text-muted-foreground">No columns.</p>;

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-left text-sm">
        <thead className="bg-muted/60">
          <tr>
            {columns.map((c) => (
              <th key={c} className="whitespace-nowrap px-3 py-2 font-medium">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-3 py-6 text-center text-muted-foreground">
                No rows
              </td>
            </tr>
          )}
          {rows.map((row, i) => (
            <tr key={i} className="border-t border-border">
              {columns.map((c) => (
                <td
                  key={c}
                  className={cn(
                    'max-w-xs truncate whitespace-nowrap px-3 py-2 font-mono text-xs',
                    row[c] === null && 'text-muted-foreground',
                  )}
                >
                  {formatCell(row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
