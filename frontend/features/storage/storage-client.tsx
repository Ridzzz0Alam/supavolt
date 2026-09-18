'use client';

import Link from 'next/link';
import { useActionState, useRef, useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import {
  createBucket,
  deleteBucket,
  deleteObject,
  getSignedUrl,
  registerObject,
  requestUploadUrl,
  type ActionState,
} from '@/lib/actions';
import type { StorageBucket, StorageObject } from '@/lib/types';
import { cn, formatBytes } from '@/lib/utils';
import { buttonClass, inputClass, Notice, secondaryButtonClass } from '@/components/ui';

export function StorageClient({ orgSlug, projectSlug, buckets, selected, objects }: {
  orgSlug: string;
  projectSlug: string;
  buckets: StorageBucket[];
  selected: StorageBucket | null;
  objects: StorageObject[];
}) {
  const router = useRouter();
  const fileInput = useRef<HTMLInputElement>(null);
  const [bucketState, bucketAction, creating] = useActionState(createBucket.bind(null, orgSlug, projectSlug), {});
  const [result, setResult] = useState<ActionState>({});
  const [isPending, startTransition] = useTransition();

  const run = (action: () => Promise<ActionState>) =>
    startTransition(async () => {
      setResult(await action());
      router.refresh();
    });

  // The presigned flow: ask the API for a URL, PUT the bytes straight to object storage, then
  // register the object. The file never passes through the API.
  const upload = (file: File) =>
    run(async () => {
      if (!selected) return { error: 'Create a bucket first' };
      const contentType = file.type || 'application/octet-stream';

      const url = await requestUploadUrl(orgSlug, projectSlug, selected.id, {
        fileName: file.name, contentType, size: file.size,
      });
      if (!url.upload) return { error: url.error };

      const put = await fetch(url.upload.uploadUrl, { method: 'PUT', body: file, headers: { 'Content-Type': contentType } })
        .catch(() => null);
      if (!put?.ok) return { error: `Upload to storage failed${put ? ` (${put.status})` : ''}` };

      const registered = await registerObject(orgSlug, projectSlug, selected.id, {
        name: file.name, size: file.size, contentType, objectKey: url.upload.objectKey,
      });
      return registered.error ? { error: registered.error } : { success: `Uploaded ${file.name}` };
    });

  const open = (object: StorageObject) =>
    startTransition(async () => {
      if (object.url) return void window.open(object.url, '_blank', 'noopener');
      const signed = await getSignedUrl(orgSlug, projectSlug, object.id);
      if (signed.url) window.open(signed.url, '_blank', 'noopener');
      else setResult({ error: signed.error });
    });

  return (
    <div className="flex flex-col gap-6 lg:flex-row">
      <aside className="shrink-0 space-y-4 lg:w-60">
        <div>
          <p className="mb-2 text-xs uppercase tracking-wide text-muted-foreground">Buckets</p>
          {buckets.length === 0 && <p className="text-sm text-muted-foreground">No buckets yet.</p>}
          <ul className="space-y-0.5">
            {buckets.map((b) => (
              <li key={b.id}>
                <Link
                  href={`?bucket=${b.id}`}
                  className={cn(
                    'flex items-center justify-between rounded-md px-2 py-1.5 text-sm hover:bg-accent',
                    b.id === selected?.id && 'bg-accent font-medium',
                  )}
                >
                  <span className="font-mono">{b.name}</span>
                  <span className="text-xs text-muted-foreground">{b.access}</span>
                </Link>
              </li>
            ))}
          </ul>
        </div>

        <form action={bucketAction} className="space-y-2 rounded-lg border border-border p-3">
          <p className="text-sm font-medium">New bucket</p>
          <input name="name" required placeholder="avatars" className={`${inputClass} font-mono`} />
          <select name="access" defaultValue="private" className={inputClass}>
            <option value="private">private (signed URLs)</option>
            <option value="public">public</option>
          </select>
          <button type="submit" disabled={creating} className={`${buttonClass} w-full`}>
            {creating ? 'Creating…' : 'Create bucket'}
          </button>
          {bucketState.error && <Notice kind="error">{bucketState.error}</Notice>}
        </form>
      </aside>

      <section className="min-w-0 flex-1">
        {!selected ? (
          <p className="text-sm text-muted-foreground">Create a bucket to start uploading files.</p>
        ) : (
          <>
            <div className="mb-4 flex flex-wrap items-center gap-2">
              <h2 className="mr-auto font-mono font-medium">{selected.name}</h2>
              <input
                ref={fileInput}
                type="file"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) upload(file);
                  e.target.value = '';
                }}
              />
              <button type="button" disabled={isPending} onClick={() => fileInput.current?.click()} className={buttonClass}>
                {isPending ? 'Working…' : 'Upload file'}
              </button>
              <button
                type="button"
                disabled={isPending}
                onClick={() => run(() => deleteBucket(orgSlug, projectSlug, selected.id))}
                className={secondaryButtonClass}
              >
                Delete bucket
              </button>
            </div>

            {result.error && <div className="mb-3"><Notice kind="error">{result.error}</Notice></div>}
            {result.success && <div className="mb-3"><Notice kind="success">{result.success}</Notice></div>}

            <div className="overflow-hidden rounded-lg border border-border">
              {objects.length === 0 && (
                <p className="px-4 py-6 text-center text-sm text-muted-foreground">No files in this bucket.</p>
              )}
              {objects.map((o) => (
                <div key={o.id} className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-2.5 text-sm last:border-b-0">
                  <button type="button" onClick={() => open(o)} className="min-w-0 flex-1 truncate text-left font-mono hover:underline">
                    {o.name}
                  </button>
                  <span className="text-xs text-muted-foreground">{o.mimeType}</span>
                  <span className="w-16 text-right text-xs text-muted-foreground">{formatBytes(o.size)}</span>
                  <button
                    type="button"
                    disabled={isPending}
                    onClick={() => run(() => deleteObject(orgSlug, projectSlug, o.id))}
                    className="text-xs text-muted-foreground hover:text-destructive disabled:opacity-60"
                  >
                    Delete
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </section>
    </div>
  );
}
