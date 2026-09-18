'use client';

import { useActionState } from 'react';
import { createOrganization } from '@/lib/actions';
import { buttonClass, inputClass, Notice } from '@/components/ui';

export function CreateOrgForm() {
  const [state, formAction, isPending] = useActionState(createOrganization, {});

  return (
    <form action={formAction} className="space-y-3">
      {state.error && <Notice kind="error">{state.error}</Notice>}
      <div className="flex gap-2">
        <input name="name" required placeholder="Organization name" className={inputClass} />
        <button type="submit" disabled={isPending} className={buttonClass}>
          {isPending ? 'Creating…' : 'Create'}
        </button>
      </div>
    </form>
  );
}
