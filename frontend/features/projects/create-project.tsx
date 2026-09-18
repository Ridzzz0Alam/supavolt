'use client';

import { useActionState } from 'react';
import { createProject } from '@/lib/actions';
import { buttonClass, Card, inputClass, Notice } from '@/components/ui';

export function CreateProject({ orgSlug }: { orgSlug: string }) {
  const [state, formAction, isPending] = useActionState(createProject.bind(null, orgSlug), {});

  return (
    <Card>
      <p className="mb-3 text-sm font-medium">New project</p>
      <form action={formAction} className="flex gap-2">
        <input name="name" required placeholder="Project name" className={inputClass} />
        <button type="submit" disabled={isPending} className={buttonClass}>
          {isPending ? 'Creating…' : 'Create'}
        </button>
      </form>

      {state.error && <div className="mt-3"><Notice kind="error">{state.error}</Notice></div>}

      {/* The service-role key is stored hashed: this is the only time it can be shown. */}
      {state.keys && state.project && (
        <div className="mt-4 space-y-3 rounded-lg border border-border bg-muted/50 p-4 text-sm">
          <p className="font-medium">
            {state.project.name} created. Copy the service-role key now — it will never be shown again.
          </p>
          <KeyField label="Project URL" value={state.project.projectUrl} />
          <KeyField label="Anon key" value={state.keys.anonKey} />
          <KeyField label="Service-role key (secret)" value={state.keys.serviceRoleKey} />
        </div>
      )}
    </Card>
  );
}

function KeyField({ label, value }: { label: string; value: string }) {
  return (
    <label className="block space-y-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <input readOnly value={value} onFocus={(e) => e.currentTarget.select()} className={`${inputClass} font-mono text-xs`} />
    </label>
  );
}
