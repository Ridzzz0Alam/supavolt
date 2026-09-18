import { getProject } from '@/lib/server-data';
import { ProjectTabs } from '@/features/projects/project-tabs';

export default async function ProjectLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ slug: string; projectSlug: string }>;
}) {
  const { slug, projectSlug } = await params;
  const project = await getProject(slug, projectSlug);

  return (
    <div className="flex min-h-full flex-col">
      <header className="border-b border-border px-4 pt-5 md:px-8">
        <p className="text-xs text-muted-foreground">Project</p>
        <h1 className="text-lg font-medium">{project.name}</h1>
        <ProjectTabs base={`/organizations/${slug}/${projectSlug}`} />
      </header>
      <div className="flex-1 px-4 py-6 md:px-8">{children}</div>
    </div>
  );
}
