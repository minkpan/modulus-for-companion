import { parseBuffer } from './configParser.js';

async function decompressGzip(uint8Array) {
  const ds = new DecompressionStream('gzip');
  const writer = ds.writable.getWriter();
  writer.write(uint8Array);
  writer.close();
  const chunks = [];
  const reader = ds.readable.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
  }
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}

async function decompressDeflateRaw(uint8Array) {
  const ds = new DecompressionStream('deflate-raw');
  const writer = ds.writable.getWriter();
  writer.write(uint8Array);
  writer.close();
  const chunks = [];
  const reader = ds.readable.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
  }
  const total = chunks.reduce((n, c) => n + c.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}

async function parseZip(uint8Array) {
  const view = new DataView(uint8Array.buffer, uint8Array.byteOffset, uint8Array.byteLength);
  let pos = 0;
  while (pos + 30 <= uint8Array.length) {
    const sig = view.getUint32(pos, true);
    if (sig !== 0x04034b50) break;
    const compression = view.getUint16(pos + 8, true);
    const compressedSize = view.getUint32(pos + 18, true);
    const filenameLen = view.getUint16(pos + 26, true);
    const extraLen = view.getUint16(pos + 28, true);
    const filenameBytes = uint8Array.slice(pos + 30, pos + 30 + filenameLen);
    const filename = new TextDecoder().decode(filenameBytes).toLowerCase();
    const dataStart = pos + 30 + filenameLen + extraLen;
    const compressedData = uint8Array.slice(dataStart, dataStart + compressedSize);
    pos = dataStart + compressedSize;

    if (filename.endsWith('/')) continue;

    try {
      let entryBytes;
      if (compression === 0) {
        entryBytes = compressedData;
      } else if (compression === 8) {
        entryBytes = await decompressDeflateRaw(compressedData);
      } else {
        continue;
      }
      const result = await tryParseBoth(entryBytes, filename);
      if (result !== null) return result;
    } catch (_) {
      continue;
    }
  }
  return null;
}

async function tryParseBoth(bytes, nameLower) {
  try { return await parseBuffer(bytes, nameLower); }
  catch (_) { return null; }
}

export async function readConfigAuto(file) {
  const buffer = await file.arrayBuffer();
  const bytes = new Uint8Array(buffer);

  if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const inner = await decompressGzip(bytes);
    return parseBuffer(inner, 'inner.gz');
  }

  if (bytes.length >= 2 && bytes[0] === 0x50 && bytes[1] === 0x4b) {
    const result = await parseZip(bytes);
    if (result !== null) return result;
    throw new Error('No parseable entry found in ZIP');
  }

  return parseBuffer(bytes, file.name.toLowerCase());
}
