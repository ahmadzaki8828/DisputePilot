"use client";

export const NOTES_MAX = 2000;

export function NotesEditor({ value, onChange, disabled }: { value: string; onChange: (v: string) => void; disabled?: boolean }) {
  const over = value.length > NOTES_MAX;
  return (
    <div>
      <label htmlFor="draft-notes" className="text-sm font-medium">Note to PayPal</label>
      <textarea
        id="draft-notes"
        className="mt-1 h-40 w-full rounded-md border border-black/20 bg-transparent p-2 text-sm dark:border-white/25"
        value={value}
        maxLength={NOTES_MAX}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      />
      <div className="flex justify-between text-xs">
        <span>{over && <span role="alert" className="text-red-600">Note is too long for PayPal.</span>}</span>
        <span data-over={over} className={over ? "text-red-600" : "opacity-70"}>{`${value.length} / ${NOTES_MAX}`}</span>
      </div>
    </div>
  );
}
