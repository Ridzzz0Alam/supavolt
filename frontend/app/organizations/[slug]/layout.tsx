import Link from 'next/link';
import { getOrganization } from '@/lib/server-data';
import { SignOutButton } from '@/components/sign-out-button';

export default async function OrgLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const org = await getOrganization(slug);

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <aside className="flex shrink-0 flex-col gap-1 border-b border-border bg-muted/40 p-4 md:w-56 md:border-r md:border-b-0">
        <Link href="/organizations" className="mb-4 font-semibold">⚡ Supavolt</Link>
        <p className="px-2 text-xs uppercase tracking-wide text-muted-foreground">Organization</p>
        <Link href="/organizations" className="rounded-md px-2 py-1.5 text-sm font-medium hover:bg-accent">
          {org.name} <span className="text-muted-foreground">⇄</span>
        </Link>
        <Link href={`/organizations/${slug}/projects`} className="rounded-md px-2 py-1.5 text-sm hover:bg-accent">
          Projects
        </Link>
        <Link href={`/organizations/${slug}/settings/members`} className="rounded-md px-2 py-1.5 text-sm hover:bg-accent">
          Members
        </Link>
        <div className="mt-auto px-2 pt-4">
          <SignOutButton />
        </div>
      </aside>
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}
