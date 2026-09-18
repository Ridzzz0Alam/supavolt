'use client';

import { useEffect, useRef, useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { setRealtime } from '@/lib/actions';
import { SupavoltRealtime, type RealtimeEvent } from '@/lib/realtime';
import { cn } from '@/lib/utils';
import { Notice } from '@/components/ui';

const MAX_EVENTS = 50;

export function RealtimeClient({ orgSlug, projectSlug, anonKey, tables, enabled }: {
  orgSlug: string;
  projectSlug: string;
  anonKey: string;
  tables: string[];
  enabled: string[];
}) {
  const router = useRouter();
  const [events, setEvents] = useState<RealtimeEvent[]>([]);
  const [status, setStatus] = useState<'connecting' | 'live' | 'error'>('connecting');
  const [error, setError] = useState<string>();
  const [isPending, startTransition] = useTransition();
  const client = useRef<SupavoltRealtime | null>(null);

  // Subscribe to every enabled table, with the project's anon key, exactly as an app would.
  const enabledKey = enabled.join(',');
  useEffect(() => {
    const realtime = new SupavoltRealtime(anonKey);
    client.current = realtime;
    let cancelled = false;

    (async () => {
      try {
        for (const table of enabledKey ? enabledKey.split(',') : []) {
          await realtime.subscribe(table, (event) =>
            setEvents((prev) => [event, ...prev].slice(0, MAX_EVENTS)));
        }
        if (!cancelled) setStatus('live');
      } catch {
        if (!cancelled) setStatus('error');
      }
    })();

    return () => {
      cancelled = true;
      void realtime.disconnect();
    };
  }, [anonKey, enabledKey]);

  const toggle = (table: string, on: boolean) =>
    startTransition(async () => {
      const res = await setRealtime(orgSlug, projectSlug, table, on);
      setError(res.error);
      router.refresh();
    });

  return (
    <div className="flex flex-col gap-6 lg:flex-row">
      <aside className="shrink-0 lg:w-60">
        <p className="mb-2 text-xs uppercase tracking-wide text-muted-foreground">Broadcast changes from</p>
        {tables.length === 0 && <p className="text-sm text-muted-foreground">No tables yet.</p>}
        <ul className="space-y-1">
          {tables.map((t) => {
            const on = enabled.includes(t);
            return (
              <li key={t}>
                <label className="flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-accent">
                  <span className="font-mono text-sm">{t}</span>
                  <input type="checkbox" checked={on} disabled={isPending} onChange={(e) => toggle(t, e.target.checked)} />
                </label>
              </li>
            );
          })}
        </ul>
        {error && <div className="mt-3"><Notice kind="error">{error}</Notice></div>}
      </aside>

      <section className="min-w-0 flex-1">
        <div className="mb-3 flex items-center gap-2 text-sm">
          <span
            className={cn(
              'inline-block size-2 rounded-full',
              status === 'live' ? 'bg-green-500' : status === 'error' ? 'bg-destructive' : 'bg-muted-foreground',
            )}
          />
          {status === 'live' ? 'Listening' : status === 'error' ? 'Could not connect' : 'Connecting…'}
          <span className="text-muted-foreground">· connected with the anon key, like a client app</span>
        </div>

        {events.length === 0 ? (
          <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
            {enabled.length === 0
              ? 'Enable a table on the left, then insert, update or delete rows to see events here.'
              : 'Waiting for changes. Try an insert from the SQL editor or the REST API.'}
          </p>
        ) : (
          <ul className="space-y-2">
            {events.map((e, i) => (
              <li key={`${e.timestamp}-${i}`} className="rounded-lg border border-border p-3">
                <div className="mb-1 flex items-center gap-2 text-xs">
                  <span className="rounded bg-muted px-1.5 py-0.5 font-medium uppercase">{e.type}</span>
                  <span className="font-mono">{e.table}</span>
                  <span className="ml-auto text-muted-foreground">{new Date(e.timestamp).toLocaleTimeString()}</span>
                </div>
                <pre className="overflow-x-auto font-mono text-xs text-muted-foreground">
                  {JSON.stringify(e.record ?? e.oldRecord)}
                </pre>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
