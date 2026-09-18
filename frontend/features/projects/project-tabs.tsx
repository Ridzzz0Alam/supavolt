'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils';

const TABS = [
  { href: 'database', label: 'Table editor' },
  { href: 'sql', label: 'SQL editor' },
  { href: 'storage', label: 'Storage' },
  { href: 'realtime', label: 'Realtime' },
  { href: 'auth', label: 'Auth' },
  { href: 'api', label: 'API' },
];

export function ProjectTabs({ base }: { base: string }) {
  const pathname = usePathname();

  return (
    <nav className="mt-3 flex gap-1 overflow-x-auto">
      {TABS.map((tab) => {
        const href = `${base}/${tab.href}`;
        const active = pathname.startsWith(href);
        return (
          <Link
            key={tab.href}
            href={href}
            className={cn(
              '-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm',
              active ? 'border-foreground font-medium' : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
