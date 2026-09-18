import { getBuckets, getObjects } from '@/lib/server-data';
import { StorageClient } from '@/features/storage/storage-client';

export default async function StoragePage({
  params,
  searchParams,
}: {
  params: Promise<{ slug: string; projectSlug: string }>;
  searchParams: Promise<{ bucket?: string }>;
}) {
  const { slug, projectSlug } = await params;
  const { bucket } = await searchParams;
  const buckets = await getBuckets(slug, projectSlug);
  const selected = buckets.find((b) => b.id === bucket) ?? buckets[0];
  const objects = selected ? await getObjects(slug, projectSlug, selected.id) : [];

  return (
    <StorageClient
      orgSlug={slug}
      projectSlug={projectSlug}
      buckets={buckets}
      selected={selected ?? null}
      objects={objects}
    />
  );
}
