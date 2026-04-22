import { useState, useEffect, useCallback } from 'react';

export default function ResultTable({ rows }) {
  const [selected, setSelected] = useState(new Set());
  const [lastIdx, setLastIdx] = useState(null);

  useEffect(() => { setSelected(new Set()); setLastIdx(null); }, [rows]);

  const handleRowClick = useCallback((e, idx) => {
    if (rows[idx].isHeader) return;
    const newSelected = new Set(selected);

    if (e.shiftKey && lastIdx !== null) {
      const [lo, hi] = [Math.min(lastIdx, idx), Math.max(lastIdx, idx)];
      for (let i = lo; i <= hi; i++) {
        if (!rows[i].isHeader) newSelected.add(i);
      }
    } else if (e.metaKey || e.ctrlKey) {
      if (newSelected.has(idx)) newSelected.delete(idx);
      else newSelected.add(idx);
    } else {
      newSelected.clear();
      newSelected.add(idx);
    }

    setSelected(newSelected);
    setLastIdx(idx);
  }, [rows, selected, lastIdx]);

  const copySelected = useCallback(() => {
    if (selected.size === 0) return;
    const lines = [...selected].sort((a, b) => a - b).map(i => {
      const r = rows[i];
      return r.page && r.page !== '' ? r.page : '  ' + r.button;
    });
    navigator.clipboard.writeText(lines.join('\n')).catch(() => {});
  }, [rows, selected]);

  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'c') copySelected();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [copySelected]);

  if (!rows || rows.length === 0) return (
    <div className="table-wrapper" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)' }}>
      No data
    </div>
  );

  return (
    <div className="table-wrapper">
      <table>
        <thead>
          <tr>
            <th className="col-page">Page</th>
            <th className="col-button">Button</th>
            <th className="col-action">Action</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            r.isHeader
              ? (
                <tr key={i} className="page-header">
                  <td colSpan={3}>{r.page}</td>
                </tr>
              ) : (
                <tr
                  key={i}
                  className={selected.has(i) ? 'selected' : ''}
                  onClick={(e) => handleRowClick(e, i)}
                >
                  <td className="col-page">{r.page}</td>
                  <td className="col-button">{r.button}</td>
                  <td className="col-action">{r.action}</td>
                </tr>
              )
          ))}
        </tbody>
      </table>
    </div>
  );
}
