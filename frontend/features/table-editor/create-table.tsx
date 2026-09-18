'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { createTable } from '@/lib/actions';
import { COLUMN_TYPES, type CreateColumnInput } from '@/lib/types';
import { cn } from '@/lib/utils';
import { buttonClass, inputClass, Notice, secondaryButtonClass } from '@/components/ui';

const newColumn = (): CreateColumnInput => ({ name: '', type: 'text', isNullable: true, isPrimaryKey: false });

export function CreateTable({ orgSlug, projectSlug }: { orgSlug: string; projectSlug: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [columns, setColumns] = useState<CreateColumnInput[]>([
    { name: 'id', type: 'bigint', isNullable: false, isPrimaryKey: true },
    newColumn(),
  ]);
  const [error, setError] = useState<string>();
  const [isPending, startTransition] = useTransition();

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className={`${secondaryButtonClass} w-full`}>
        + New table
      </button>
    );
  }

  const update = (i: number, patch: Partial<CreateColumnInput>) =>
    setColumns((cols) => cols.map((c, j) => (j === i ? { ...c, ...patch } : c)));

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const payload = {
        name,
        columns: columns
          .filter((c) => c.name.trim())
          .map((c) => ({ ...c, defaultValue: c.defaultValue || undefined })),
      };
      const res = await createTable(orgSlug, projectSlug, payload);
      if (res.error) return setError(res.error);
      setOpen(false);
      router.push(`?table=${encodeURIComponent(name)}`);
      router.refresh();
    });
  };

  return (
    <form onSubmit={submit} className="space-y-3 rounded-lg border border-border p-3 lg:fixed lg:inset-x-0 lg:top-24 lg:z-10 lg:mx-auto lg:w-[640px] lg:bg-card lg:p-5 lg:shadow-lg">
      <p className="text-sm font-medium">New table</p>
      {error && <Notice kind="error">{error}</Notice>}
      <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="table_name" className={`${inputClass} font-mono`} />

      <div className="space-y-2">
        {columns.map((c, i) => (
          <div key={i} className="flex flex-wrap items-center gap-2 text-xs">
            <input
              value={c.name}
              onChange={(e) => update(i, { name: e.target.value })}
              placeholder="column"
              className={cn(inputClass, 'w-auto min-w-0 flex-1 font-mono')}
            />
            <select
              value={c.type}
              onChange={(e) => update(i, { type: e.target.value as CreateColumnInput['type'] })}
              className={cn(inputClass, 'w-28')}
            >
              {COLUMN_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
            <input
              value={c.defaultValue ?? ''}
              onChange={(e) => update(i, { defaultValue: e.target.value })}
              placeholder="default"
              className={cn(inputClass, 'w-24')}
            />
            <label className="flex items-center gap-1">
              <input type="checkbox" checked={c.isPrimaryKey} onChange={(e) => update(i, { isPrimaryKey: e.target.checked, isNullable: e.target.checked ? false : c.isNullable })} /> pk
            </label>
            <label className="flex items-center gap-1">
              <input type="checkbox" checked={c.isNullable} disabled={c.isPrimaryKey} onChange={(e) => update(i, { isNullable: e.target.checked })} /> null
            </label>
            <button type="button" onClick={() => setColumns((cols) => cols.filter((_, j) => j !== i))} className="px-1 text-muted-foreground hover:text-destructive" aria-label="Remove column">
              ✕
            </button>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => setColumns((cols) => [...cols, newColumn()])} className={secondaryButtonClass}>
          + Column
        </button>
        <span className="flex-1" />
        <button type="button" onClick={() => setOpen(false)} className={secondaryButtonClass}>Cancel</button>
        <button type="submit" disabled={isPending} className={buttonClass}>{isPending ? 'Creating…' : 'Create table'}</button>
      </div>
    </form>
  );
}
