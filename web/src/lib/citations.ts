export function citationIdsInText(text: string): string[] {
  return [...text.matchAll(/\[((?:dispute|order|shipment|msg|policy):[^\]\s]+)\]/g)].map((m) => m[1]);
}

export function isHighlighted(sourceId: string, activeId: string | null): boolean {
  return activeId !== null && sourceId === activeId;
}
