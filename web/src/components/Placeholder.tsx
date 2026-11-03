export function Placeholder({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <section className="mx-auto max-w-3xl p-8">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="mt-2 text-sm opacity-70">Placeholder page. Session C builds this screen against the API contract.</p>
      {children}
    </section>
  );
}
