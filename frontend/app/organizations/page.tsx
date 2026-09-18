import Link from 'next/link';
import { getMyOrganizations } from '@/lib/server-data';
import { Card, PageHeader } from '@/components/ui';
import { SignOutButton } from '@/components/sign-out-button';
import { CreateOrgForm } from '@/features/orgs/create-org-form';

export default async function OrganizationsPage() {
  const orgs = await getMyOrganizations();

  return (
    <main className="mx-auto w-full max-w-3xl px-4 py-10">
      <div className="mb-8 flex items-center justify-between">
        <span className="font-semibold">⚡ Supavolt</span>
        <SignOutButton />
      </div>

      <PageHeader title="Organizations" description="Pick an organization to see its projects." />

      <div className="grid gap-3">
        {orgs.map((org) => (
          <Link key={org.id} href={`/organizations/${org.slug}/projects`}>
            <Card className="flex items-center justify-between hover:bg-accent">
              <div>
                <p className="font-medium">{org.name}</p>
                <p className="text-sm text-muted-foreground">
                  {org.projectCount} projects · {org.memberCount} members · {org.role}
                </p>
              </div>
              <span className="text-muted-foreground">→</span>
            </Card>
          </Link>
        ))}
      </div>

      <Card className="mt-8">
        <p className="mb-3 text-sm font-medium">New organization</p>
        <CreateOrgForm />
      </Card>
    </main>
  );
}
