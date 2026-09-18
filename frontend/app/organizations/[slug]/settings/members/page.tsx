import { getMembers, getOrganization } from '@/lib/server-data';
import { PageHeader } from '@/components/ui';
import { MembersClient } from '@/features/members/members-client';

export default async function MembersPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const [org, members] = await Promise.all([getOrganization(slug), getMembers(slug)]);

  return (
    <main className="mx-auto w-full max-w-4xl px-4 py-8 md:px-8">
      <PageHeader title="Members" description={`People with access to ${org.name}.`} />
      <MembersClient orgSlug={slug} members={members} isAdmin={org.role === 'admin'} />
    </main>
  );
}
