import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { Geist, Geist_Mono } from "next/font/google";
import { ModeBadgesServer } from "@/components/ModeBadgesServer";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "DisputePilot",
  description: "AI copilot that drafts responses to PayPal disputes, with a human approving every submission.",
};

const NAV = [
  ["/", "Inbox"],
  ["/metrics", "Metrics"],
  ["/analytics", "Analytics"],
  ["/evals", "Evals"],
  ["/about", "About"],
] as const;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="min-h-full flex flex-col">
        <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-white focus:p-2 focus:text-black">Skip to content</a>
        <header className="border-b border-black/10 dark:border-white/15">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
            <Link href="/" className="text-lg font-bold tracking-tight">Dispute<span className="text-indigo-600">Pilot</span></Link>
            <nav aria-label="Main" className="flex gap-4 text-sm">
              {NAV.map(([href, label]) => <Link key={href} href={href} className="underline-offset-4 hover:underline">{label}</Link>)}
            </nav>
            <div className="ml-auto">
              <Suspense fallback={<span className="text-xs opacity-60">Loading modes…</span>}>
                <ModeBadgesServer />
              </Suspense>
            </div>
          </div>
        </header>
        <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">{children}</main>
      </body>
    </html>
  );
}
