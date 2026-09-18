import { signOut } from '@/lib/actions';

export function SignOutButton() {
  return (
    <form action={signOut}>
      <button type="submit" className="text-sm text-muted-foreground hover:text-foreground">
        Sign out
      </button>
    </form>
  );
}
