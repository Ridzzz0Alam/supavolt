import Link from 'next/link';
import { getTableInfo, getTableRows, getTables } from '@/lib/server-data';
import { cn } from '@/lib/utils';
import { DataGrid } from '@/components/ui';
import { CreateTable } from '@/features/table-editor/create-table';

export default async function DatabasePage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string; projectSlug: string }>;
  searchParams: Promise<{ table?: string }>;
}) {
  const { slug, projectSlug } = await params;
  const { table } = await searchParams;
  const tables = await getTables(slug, projectSlug);
  const selected = table && tables.includes(table) ? table : tables[0];

  const [info, rows] = selected
    ? await Promise.all([
        getTableInfo(slug, projectSlug, selected),
        getTableRows(slug, projectSlug, selected),
      ])
    : [null, null];

  return (
    <div className="flex flex-col gap-6 lg:flex-row">
      <aside className="shrink-0 lg:w-52">
        <p className="mb-2 text-xs uppercase tracking-wide text-muted-foreground">Tables</p>
        {tables.length === 0 && <p className="text-sm text-muted-foreground">No tables yet.</p>}
        <ul className="space-y-0.5">
          {tables.map((t) => (
            <li key={t}>
              <Link
                href={`?table=${encodeURIComponent(t)}`}
                className={cn(
                  'block rounded-md px-2 py-1.5 font-mono text-sm hover:bg-accent',
                  t === selected && 'bg-accent font-medium',
                )}
              >
                {t}
              </Link>
            </li>
          ))}
        </ul>
        <div className="mt-4">
          <CreateTable orgSlug={slug} projectSlug={projectSlug} />
        </div>
      </aside>

      <section className="min-w-0 flex-1">
        {info && rows ? (
          <>
            <div className="mb-3 flex items-baseline justify-between gap-4">
              <h2 className="font-mono font-medium">{info.name}</h2>
              <span className="text-sm text-muted-foreground">{rows.count} rows</span>
            </div>
            <p className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
              {info.columns.map((c) => (
                <span key={c.name} className="font-mono">
                  {c.name} <span className="opacity-70">{c.type}{c.isPrimaryKey ? ' · pk' : ''}{c.isNullable ? '' : ' · not null'}</span>
                </span>
              ))}
            </p>
            <DataGrid columns={info.columns.map((c) => c.name)} rows={rows.rows} />
            <p className="mt-3 text-xs text-muted-foreground">
              Add or change rows from the SQL editor, or through the REST API with the service-role key.
            </p>
          </>
        ) : (
          <p className="text-sm text-muted-foreground">Create a table to get started.</p>
        )}
      </section>
    </div>
  );
}
