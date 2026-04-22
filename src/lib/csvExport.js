function cell(s) {
  return '"' + (s ?? '').replace(/"/g, '""') + '"';
}

function download(filename, content) {
  const blob = new Blob([content], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function exportCurrent(rows, configName, moduleLabel) {
  const lines = ['Page,Button,Action'];
  for (const r of rows) {
    lines.push(`${cell(r.page)},${cell(r.button)},${cell(r.action)}`);
  }
  const safeLabel = moduleLabel.replace(/\s+/g, '_');
  download(`${safeLabel}.${configName}.csv`, lines.join('\n'));
}

export function exportAll(allModuleRows, configName) {
  const lines = ['Module,Page,Button,Action'];
  for (const [moduleLabel, rows] of allModuleRows) {
    for (const r of rows) {
      const moduleCell = r.isHeader ? cell(moduleLabel) : '""';
      lines.push(`${moduleCell},${cell(r.page)},${cell(r.button)},${cell(r.action)}`);
    }
  }
  download(`${configName}.csv`, lines.join('\n'));
}
