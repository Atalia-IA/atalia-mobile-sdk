/**
 * Deixa a lista de `appLinkHosts` só com o host, em minúsculas e sem repetição. Aceita o que as
 * pessoas colam: "https://app.exemplo.com.br/cursos", "Exemplo.com.br", "*.exemplo.com.br".
 * A camada nativa compara o host do link tocado com esta lista (o host ou um subdomínio dele).
 */
export function normalizeAppLinkHosts(hosts: unknown): string[] {
  if (!Array.isArray(hosts)) return [];
  const clean = new Set<string>();
  for (const item of hosts) {
    if (typeof item !== 'string') continue;
    const host = item
      .trim()
      .toLowerCase()
      .replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
      .replace(/[/?#].*$/, '')
      .replace(/^.*@/, '')
      .replace(/:\d+$/, '')
      .replace(/^\*\./, '')
      .replace(/^\.+|\.+$/g, '');
    if (host && /^[a-z0-9.-]+$/.test(host)) clean.add(host);
  }
  return [...clean];
}
