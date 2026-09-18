'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { runSql } from '@/lib/actions';
import type { QueryHistoryItem, QueryResult } from '@/lib/types';
import { buttonClass, DataGrid, Notice } from '@/components/ui';

export function SqlEditor({ orgSlug, projectSlug, history }: {
  orgSlug: string;
  projectSlug: string;
  history: QueryHistoryItem[];
}) {
  const router = useRouter();
  const [sql, setSql] = useState('select now();');
  const [result, setResult] = useState<QueryResult>();
  const [error, setError] = useState<string>();
  const [isPending, startTransition] = useTransition();

  const run = () =>
    startTransition(async () => {
      setError(undefined);
      const res = await runSql(orgSlug, projectSlug, sql);
      setResult(res.result);
      setError(res.error);
      router.refresh();
    });

  return (
    <div className="flex flex-col gap-6 lg:flex-row">
      <div className="min-w-0 flex-1 space-y-3">
        <textarea
          value={sql}
          onChange={(e) => setSql(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              run();
            }
          }}
          spellCheck={false}
          rows={8}
          className="w-full rounded-md border border-input bg-transparent p-3 font-mono text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
        />
        <div className="flex items-center gap-3">
          <button type="button" onClick={run} disabled={isPending || !sql.trim()} className={buttonClass}>
            {isPending ? 'Running…' : 'Run'}
          </button>
          <span className="text-xs text-muted-foreground">Ctrl + Enter · one statement per run</span>
        </div>

        {error && <Notice kind="error">{error}</Notice>}
        {result && (
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">
              {result.command} · {result.rowCount} rows · {result.executionTimeMs} ms
            </p>
            {result.columns.length > 0 && <DataGrid columns={result.columns} rows={result.rows} />}
          </div>
        )}
      </div>

      <aside className="shrink-0 lg:w-64">
        <p className="mb-2 text-xs uppercase tracking-wide text-muted-foreground">History</p>
        {history.length === 0 && <p className="text-sm text-muted-foreground">No queries yet.</p>}
        <ul className="space-y-1">
          {history.map((h) => (
            <li key={h.id}>
              <button
                type="button"
                onClick={() => setSql(h.sql)}
                className="block w-full truncate rounded-md px-2 py-1.5 text-left font-mono text-xs hover:bg-accent"
                title={h.sql}
              >
                {h.sql}
              </button>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
