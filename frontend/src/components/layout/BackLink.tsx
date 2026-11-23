import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

export interface BackLinkProps {
  to: string;
  /** Accessible name, such as "Back to sign in". */
  label: string;
}

/**
 * The 44px icon link that leads a standalone page header.
 */
export const BackLink = ({ to, label }: BackLinkProps) => (
  <Link
    to={to}
    aria-label={label}
    title={label}
    className="text-foreground hover:bg-accent inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg transition-colors"
  >
    <ArrowLeft size={20} aria-hidden="true" />
  </Link>
);
