import { apiGet } from '@/lib/api';
import type { AuthProviders } from '@/lib/types';
import { LoginForm } from './login-form';

const ERRORS: Record<string, string> = {
  oauth: 'That sign-in provider is not available. Use your email and password instead.',
};

async function loadProviders(): Promise<AuthProviders> {
  try {
    return await apiGet<AuthProviders>('/auth/providers');
  } catch {
    // An unreachable API still renders the form; the sign-in attempt reports the real error.
    return { google: false, github: false };
  }
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; invite?: string }>;
}) {
  const { error, invite } = await searchParams;
  const providers = await loadProviders();

  return <LoginForm providers={providers} invite={invite} initialError={error ? ERRORS[error] : undefined} />;
}
