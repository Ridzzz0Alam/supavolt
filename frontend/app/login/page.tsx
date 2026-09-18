'use client';

import Link from 'next/link';
import { useActionState } from 'react';
import { login } from '@/lib/actions';

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:5000/api';

export default function LoginPage() {
  const [state, formAction, isPending] = useActionState(login, {});

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/40 px-4 py-12">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-8 shadow-sm">
        <h1 className="text-xl font-medium">Welcome back</h1>
        <p className="mt-1 text-sm text-muted-foreground">Sign in to your Supavolt account</p>

        {state.error && (
          <p className="mt-4 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {state.error}
          </p>
        )}

        <form action={formAction} className="mt-6 space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="email" className="text-sm font-medium">
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              required
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="password" className="text-sm font-medium">
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              className="h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>

          <button
            type="submit"
            disabled={isPending}
            className="h-10 w-full rounded-md bg-primary text-sm font-medium text-primary-foreground disabled:opacity-60"
          >
            {isPending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="my-6 flex items-center gap-3 text-xs text-muted-foreground">
          <span className="h-px flex-1 bg-border" />
          or continue with
          <span className="h-px flex-1 bg-border" />
        </div>

        {/* Full page navigation, not fetch: the OAuth handler sets its own state cookie. */}
        <div className="space-y-3">
          <a
            href={`${API_URL}/auth/google`}
            className="flex h-10 items-center justify-center rounded-md border border-input text-sm font-medium hover:bg-accent"
          >
            Continue with Google
          </a>
          <a
            href={`${API_URL}/auth/github`}
            className="flex h-10 items-center justify-center rounded-md border border-input text-sm font-medium hover:bg-accent"
          >
            Continue with GitHub
          </a>
        </div>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Don&apos;t have an account?{' '}
          <Link href="/register" className="font-medium text-foreground hover:underline">
            Sign up
          </Link>
        </p>
      </div>
    </div>
  );
}
