import { getSqlHistory } from '@/lib/server-data';
import { SqlEditor } from '@/features/sql-editor/sql-editor';

export default async function SqlPage({ params }: { params: Promise<{ slug: string; projectSlug: string }> }) {
  const { slug, projectSlug } = await params;
  const history = await getSqlHistory(slug, projectSlug);

  return <SqlEditor orgSlug={slug} projectSlug={projectSlug} history={history} />;
}
