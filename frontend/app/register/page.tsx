'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useActionState } from 'react';
import { register } from '@/lib/actions';

// useSearchParams needs a boundary, or the page cannot be prerendered.
export default function RegisterPage() {
  return (
    <Suspense>
      <RegisterForm />
    </Suspense>
  );
}

function RegisterForm() {
  const [state, formAction, isPending] = useActionState(register, {});
  const invite = useSearchParams().get('invite');

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/40 px-4 py-12">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-sm">
        <h1 className="text-xl font-medium">Create an account</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {invite ? 'Create an account to accept your invitation' : 'You get a personal organization to start with'}
        </p>

        {state.error && (
          <p className="mt-4 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {state.error}
          </p>
        )}

        <form action={formAction} className="mt-6 space-y-4">
          {invite && <input type="hidden" name="invite" value={invite} />}
          {[
            { id: 'name', label: 'Name', type: 'text', autoComplete: 'name' },
            { id: 'email', label: 'Email', type: 'email', autoComplete: 'email' },
            { id: 'password', label: 'Password', type: 'password', autoComplete: 'new-password' },
          ].map((field) => (
            <div key={field.id} className="space-y-1.5">
              <label htmlFor={field.id} className="text-sm font-medium">
                {field.label}
              </label>
              <input
                id={field.id}
                name={field.id}
                type={field.type}
                autoComplete={field.autoComplete}
                required
                minLength={field.id === 'password' ? 8 : undefined}
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>
          ))}

          <button
            type="submit"
            disabled={isPending}
            className="h-10 w-full rounded-md bg-primary text-sm font-medium text-primary-foreground disabled:opacity-60"
          >
            {isPending ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Already have an account?{' '}
          <Link href={invite ? `/login?invite=${encodeURIComponent(invite)}` : '/login'} className="font-medium text-foreground hover:underline">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
