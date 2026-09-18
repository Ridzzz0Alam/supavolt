'use client';

import { useActionState } from 'react';
import { updateAuthSettings } from '@/lib/actions';
import type { ProjectOAuthSettings } from '@/lib/types';
import { buttonClass, Card, inputClass, Notice } from '@/components/ui';

export function AuthSettingsForm({ orgSlug, projectSlug, settings }: {
  orgSlug: string;
  projectSlug: string;
  settings: ProjectOAuthSettings;
}) {
  const [state, formAction, isPending] = useActionState(updateAuthSettings.bind(null, orgSlug, projectSlug), {});

  return (
    <form action={formAction} className="space-y-4">
      {state.error && <Notice kind="error">{state.error}</Notice>}
      {state.success && <Notice kind="success">{state.success}</Notice>}

      <Card className="space-y-3">
        <Field label="Site URL" name="siteUrl" defaultValue={settings.siteUrl ?? ''} placeholder="https://myapp.com" />
        <label className="block space-y-1">
          <span className="text-sm">Redirect URLs</span>
          <textarea
            name="redirectUrls"
            rows={3}
            defaultValue={settings.redirectUrls.join('\n')}
            placeholder="One per line"
            className="w-full rounded-md border border-input bg-transparent p-2 font-mono text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </label>
      </Card>

      <Provider name="google" label="Google" clientId={settings.googleClientId} configured={settings.googleConfigured} />
      <Provider name="github" label="GitHub" clientId={settings.githubClientId} configured={settings.githubConfigured} />

      <button type="submit" disabled={isPending} className={buttonClass}>{isPending ? 'Saving…' : 'Save settings'}</button>
    </form>
  );
}

/** Secrets are write-only: the API reports only whether one is set, never its value. */
function Provider({ name, label, clientId, configured }: {
  name: 'google' | 'github';
  label: string;
  clientId: string | null;
  configured: boolean;
}) {
  return (
    <Card className="space-y-3">
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium">{label}</p>
        <span className="text-xs text-muted-foreground">{configured ? 'Secret set' : 'Not configured'}</span>
      </div>
      <Field label="Client ID" name={`${name}ClientId`} defaultValue={clientId ?? ''} />
      <Field
        label="Client secret"
        name={`${name}ClientSecret`}
        type="password"
        placeholder={configured ? 'Leave blank to keep the current secret' : ''}
      />
      {configured && (
        <label className="flex items-center gap-2 text-xs text-muted-foreground">
          <input type="checkbox" name={`${name}ClientSecretClear`} /> Remove the stored secret
        </label>
      )}
    </Card>
  );
}

function Field({ label, name, type = 'text', defaultValue, placeholder }: {
  label: string;
  name: string;
  type?: string;
  defaultValue?: string;
  placeholder?: string;
}) {
  return (
    <label className="block space-y-1">
      <span className="text-sm">{label}</span>
      <input name={name} type={type} defaultValue={defaultValue} placeholder={placeholder} autoComplete="off" className={inputClass} />
    </label>
  );
}
