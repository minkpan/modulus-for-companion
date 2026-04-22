import jsYaml from 'js-yaml';

export async function parseBuffer(uint8Array, nameLower) {
  const text = new TextDecoder('utf-8').decode(uint8Array);
  const head = text.slice(0, 1024).trim();
  const looksJson = nameLower.endsWith('.json') || head.startsWith('{') || head.startsWith('[');

  if (looksJson) {
    try { return JSON.parse(text); } catch (_) {}
    try {
      const r = jsYaml.load(text, { maxAliases: Infinity });
      if (r && typeof r === 'object') return r;
    } catch (_) {}
  } else {
    try {
      const r = jsYaml.load(text, { maxAliases: Infinity });
      if (r && typeof r === 'object') return r;
    } catch (_) {}
    try { return JSON.parse(text); } catch (_) {}
  }

  throw new Error('Unsupported or malformed config');
}
