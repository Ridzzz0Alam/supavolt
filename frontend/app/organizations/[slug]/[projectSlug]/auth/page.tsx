import { getProjectAuthSettings, getProjectAuthUsers } from '@/lib/server-data';
import { DataGrid } from '@/components/ui';
import { AuthSettingsForm } from '@/features/project-auth/auth-settings-form';

export default async function ProjectAuthPage({ params }: { params: Promise<{ slug: string; projectSlug: string }> }) {
  const { slug, projectSlug } = await params;
  const [users, settings] = await Promise.all([
    getProjectAuthUsers(slug, projectSlug),
    getProjectAuthSettings(slug, projectSlug),
  ]);

  return (
    <div className="flex flex-col gap-8 xl:flex-row">
      <section className="min-w-0 flex-1">
        <h2 className="mb-1 font-medium">Users</h2>
        <p className="mb-3 text-sm text-muted-foreground">
          People who signed up to this project&apos;s app through <span className="font-mono">/auth/signup</span>.
        </p>
        <DataGrid
          columns={['email', 'provider', 'emailVerified', 'createdAt']}
          rows={users.map((u) => ({ ...u, createdAt: new Date(u.createdAt).toLocaleString() }))}
        />
      </section>

      <section className="shrink-0 xl:w-[420px]">
        <h2 className="mb-1 font-medium">Settings</h2>
        <p className="mb-3 text-sm text-muted-foreground">Where sign-in may redirect, and OAuth credentials.</p>
        <AuthSettingsForm orgSlug={slug} projectSlug={projectSlug} settings={settings} />
      </section>
    </div>
  );
}
