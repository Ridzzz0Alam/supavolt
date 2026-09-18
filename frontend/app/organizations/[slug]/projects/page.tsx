import Link from 'next/link';
import { getProjects } from '@/lib/server-data';
import { Card, PageHeader } from '@/components/ui';
import { CreateProject } from '@/features/projects/create-project';

export default async function ProjectsPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const projects = await getProjects(slug);

  return (
    <main className="mx-auto w-full max-w-4xl px-4 py-8 md:px-8">
      <PageHeader title="Projects" description="Each project is its own Postgres schema with a REST API." />

      <CreateProject orgSlug={slug} />

      {projects.length === 0 ? (
        <p className="mt-6 text-sm text-muted-foreground">No projects yet. Create your first one above.</p>
      ) : (
        <div className="mt-6 grid gap-3 sm:grid-cols-2">
          {projects.map((p) => (
            <Link key={p.id} href={`/organizations/${slug}/${p.slug}/database`}>
              <Card className="h-full hover:bg-accent">
                <p className="font-medium">{p.name}</p>
                <p className="mt-1 font-mono text-xs text-muted-foreground">{p.dbSchema}</p>
                <p className="mt-3 text-xs text-muted-foreground">
                  Created {new Date(p.createdAt).toLocaleDateString()}
                </p>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </main>
  );
}
