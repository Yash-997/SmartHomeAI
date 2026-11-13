import type { ReactNode, SVGProps } from 'react'

/** A small set of line icons (24px grid, 1.75 stroke). They inherit the text colour and are hidden from assistive tech. */
function Icon({ children, ...rest }: SVGProps<SVGSVGElement> & { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="20"
      height="20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  )
}

type P = SVGProps<SVGSVGElement>

export const HomeIcon = (p: P) => <Icon {...p}><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5" /></Icon>
export const DevicesIcon = (p: P) => <Icon {...p}><rect x="6" y="6" width="12" height="12" rx="2" /><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4" /></Icon>
export const BoltIcon = (p: P) => <Icon {...p}><path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z" /></Icon>
export const ShieldIcon = (p: P) => <Icon {...p}><path d="M12 3 4.5 6v5.5c0 4.5 3.1 8 7.5 9.5 4.4-1.5 7.5-5 7.5-9.5V6L12 3Z" /><path d="m9 12 2.2 2.2L15.5 10" /></Icon>
export const SparkIcon = (p: P) => <Icon {...p}><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" /><circle cx="12" cy="12" r="3" /></Icon>
export const ChatIcon = (p: P) => <Icon {...p}><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12Z" /></Icon>
export const PulseIcon = (p: P) => <Icon {...p}><path d="M3 12h4l2.5-7 5 14 2.5-7H21" /></Icon>
export const ThermometerIcon = (p: P) => <Icon {...p}><path d="M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0Z" /></Icon>
export const DropletIcon = (p: P) => <Icon {...p}><path d="M12 3s6 6.2 6 10.5a6 6 0 0 1-12 0C6 9.2 12 3 12 3Z" /></Icon>
export const MotionIcon = (p: P) => <Icon {...p}><circle cx="13" cy="4.5" r="1.8" /><path d="m9 21 2.5-6L9 12.5 10.5 8l3.5 1.5 2.5 3M11.5 15l3.5 1.5V21" /></Icon>
export const FlameIcon = (p: P) => <Icon {...p}><path d="M12 3c.5 3-1.5 4.5-3 6.5S7 13 7 14.5a5 5 0 0 0 10 0c0-2-1-3.5-2-4.5 0 1.5-.7 2.5-1.8 2.5C13.5 9.5 13 6 12 3Z" /></Icon>
export const PlusIcon = (p: P) => <Icon {...p}><path d="M12 5v14M5 12h14" /></Icon>
export const ChevronRightIcon = (p: P) => <Icon {...p}><path d="m9 6 6 6-6 6" /></Icon>
export const CheckIcon = (p: P) => <Icon {...p}><path d="m5 12.5 4.5 4.5L19 7.5" /></Icon>
export const AlertIcon = (p: P) => <Icon {...p}><path d="M12 3 2.5 20h19L12 3Z" /><path d="M12 10v4M12 17.5v.01" /></Icon>
export const LogoutIcon = (p: P) => <Icon {...p}><path d="M9 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3" /><path d="m16 8 4 4-4 4M20 12H9" /></Icon>
export const MenuIcon = (p: P) => <Icon {...p}><circle cx="5" cy="12" r="1.2" /><circle cx="12" cy="12" r="1.2" /><circle cx="19" cy="12" r="1.2" /></Icon>
export const CloseIcon = (p: P) => <Icon {...p}><path d="M6 6l12 12M18 6 6 18" /></Icon>
export const ClockIcon = (p: P) => <Icon {...p}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></Icon>
export const ArrowRightIcon = (p: P) => <Icon {...p}><path d="M5 12h14M13 6l6 6-6 6" /></Icon>
export const ChevronDownIcon = (p: P) => <Icon {...p}><path d="m6 9 6 6 6-6" /></Icon>
export const PencilIcon = (p: P) => <Icon {...p}><path d="M4 20h4L19 9a2.1 2.1 0 0 0-4-4L4 16v4Z" /><path d="m13.5 6.5 4 4" /></Icon>
export const TrashIcon = (p: P) => <Icon {...p}><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-12M9 7V4h6v3" /></Icon>
export const SendIcon = (p: P) => <Icon {...p}><path d="M21 3 10 14M21 3l-7 18-4-7-7-4 18-7Z" /></Icon>
export const LockIcon = (p: P) => <Icon {...p}><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></Icon>
export const MoonIcon = (p: P) => <Icon {...p}><path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" /></Icon>
export const SunHomeIcon = (p: P) => <Icon {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" /></Icon>
