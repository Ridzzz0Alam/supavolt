import { getProject, getTables } from '@/lib/server-data';
import { Card } from '@/components/ui';

export default async function ApiPage({ params }: { params: Promise<{ slug: string; projectSlug: string }> }) {
  const { slug, projectSlug } = await params;
  const [project, tables] = await Promise.all([getProject(slug, projectSlug), getTables(slug, projectSlug)]);
  const table = tables.find((t) => t !== 'auth_users') ?? 'your_table';

  const examples = [
    ['Read rows', `curl "${project.projectUrl}/rest/${table}?limit=10" \\\n  -H "Authorization: Bearer $ANON_KEY"`],
    ['Filter and order', `curl "${project.projectUrl}/rest/${table}?id=gt.10&order=id.desc" \\\n  -H "Authorization: Bearer $ANON_KEY"`],
    ['Insert (service-role key only)', `curl -X POST "${project.projectUrl}/rest/${table}" \\\n  -H "Authorization: Bearer $SERVICE_ROLE_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '{ ... }'`],
  ];

  return (
    <div className="max-w-3xl space-y-4">
      <Card className="space-y-3">
        <Field label="Project URL" value={project.projectUrl} />
        <Field label="Anon key (safe for browsers, read-only)" value={project.anonKey} />
        <p className="text-xs text-muted-foreground">
          The service-role key is only shown once, when the project is created. It is stored hashed and cannot be retrieved.
        </p>
      </Card>

      {examples.map(([title, code]) => (
        <div key={title}>
          <p className="mb-1.5 text-sm font-medium">{title}</p>
          <pre className="overflow-x-auto rounded-lg bg-muted p-3 font-mono text-xs">{code}</pre>
        </div>
      ))}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="break-all font-mono text-xs">{value}</p>
    </div>
  );
}
