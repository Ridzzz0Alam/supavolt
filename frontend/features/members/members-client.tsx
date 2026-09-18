'use client';

import { useActionState, useState, useTransition } from 'react';
import { inviteMember, removeMember, updateMemberRole, type ActionState } from '@/lib/actions';
import type { OrgMember, OrgRole } from '@/lib/types';
import { cn } from '@/lib/utils';
import { buttonClass, Card, inputClass, Notice } from '@/components/ui';

export function MembersClient({ orgSlug, members, isAdmin }: {
  orgSlug: string;
  members: OrgMember[];
  isAdmin: boolean;
}) {
  const [inviteState, inviteAction, inviting] = useActionState(inviteMember.bind(null, orgSlug), {});
  const [result, setResult] = useState<ActionState>({});
  const [isPending, startTransition] = useTransition();

  const run = (action: () => Promise<ActionState>) => startTransition(async () => setResult(await action()));

  return (
    <div className="space-y-6">
      {isAdmin && (
        <Card>
          <p className="mb-3 text-sm font-medium">Invite a member</p>
          <form action={inviteAction} className="flex gap-2">
            <input name="email" type="email" required placeholder="teammate@example.com" className={inputClass} />
            <button type="submit" disabled={inviting} className={buttonClass}>{inviting ? 'Sending…' : 'Invite'}</button>
          </form>
          <p className="mt-2 text-xs text-muted-foreground">
            They join as a developer. In development the invite link is printed in the API log.
          </p>
          {inviteState.error && <div className="mt-3"><Notice kind="error">{inviteState.error}</Notice></div>}
          {inviteState.success && <div className="mt-3"><Notice kind="success">{inviteState.success}</Notice></div>}
        </Card>
      )}

      {result.error && <Notice kind="error">{result.error}</Notice>}
      {result.success && <Notice kind="success">{result.success}</Notice>}

      <div className="overflow-hidden rounded-xl border border-border">
        {members.map((m) => (
          <div key={m.id} className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3 last:border-b-0">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{m.user.name ?? m.user.email}</p>
              <p className="truncate text-xs text-muted-foreground">{m.user.email}</p>
            </div>

            {isAdmin ? (
              <>
                {/* The original's role select had a dead onValueChange handler; this one saves. */}
                <select
                  value={m.role}
                  disabled={isPending}
                  onChange={(e) => run(() => updateMemberRole(orgSlug, m.id, e.target.value as OrgRole))}
                  className={cn(inputClass, 'w-32')}
                  aria-label={`Role for ${m.user.email}`}
                >
                  <option value="admin">admin</option>
                  <option value="developer">developer</option>
                </select>
                <button
                  type="button"
                  disabled={isPending}
                  onClick={() => run(() => removeMember(orgSlug, m.id))}
                  className="text-sm text-muted-foreground hover:text-destructive disabled:opacity-60"
                >
                  Remove
                </button>
              </>
            ) : (
              <span className="rounded-md bg-muted px-2 py-1 text-xs">{m.role}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
