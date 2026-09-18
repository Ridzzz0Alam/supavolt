import { redirect } from 'next/navigation';
import { getMyOrganizations } from '@/lib/server-data';

/** Entry point after sign-in: one org goes straight to its projects, otherwise pick one. */
export default async function DashboardPage() {
  const orgs = await getMyOrganizations();

  if (orgs.length === 1) redirect(`/organizations/${orgs[0].slug}/projects`);
  redirect('/organizations');
}
