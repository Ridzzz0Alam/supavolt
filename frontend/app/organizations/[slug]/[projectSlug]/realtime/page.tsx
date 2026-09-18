import { getProject, getRealtimeTables, getTables } from '@/lib/server-data';
import { RealtimeClient } from '@/features/realtime/realtime-client';

export default async function RealtimePage({ params }: { params: Promise<{ slug: string; projectSlug: string }> }) {
  const { slug, projectSlug } = await params;
  const [project, tables, enabled] = await Promise.all([
    getProject(slug, projectSlug),
    getTables(slug, projectSlug),
    getRealtimeTables(slug, projectSlug),
  ]);

  return (
    <RealtimeClient
      orgSlug={slug}
      projectSlug={projectSlug}
      anonKey={project.anonKey}
      tables={tables.filter((t) => t !== 'auth_users')}
      enabled={enabled}
    />
  );
}
