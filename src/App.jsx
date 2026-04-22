import { useState, useEffect, useRef, useCallback } from 'react';
import ResultTable from './components/ResultTable.jsx';
import { readConfigAuto } from './lib/fileIngestion.js';
import { scanUsage, buildInstanceLabels } from './lib/scanUsage.js';
import { buildRowsForInstance, buildAllModuleRows } from './lib/buildRows.js';
import { exportCurrent, exportAll } from './lib/csvExport.js';

function getInitialTheme() {
  const saved = localStorage.getItem('theme');
  if (saved) return saved;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export default function App() {
  const [theme, setTheme] = useState(getInitialTheme);
  const [status, setStatus] = useState('Drop a .companionconfig file anywhere, or click Open File.');
  const [configName, setConfigName] = useState(null);
  const [dropdownEntries, setDropdownEntries] = useState([]);
  const [selectedEntry, setSelectedEntry] = useState(null);
  const [tableRows, setTableRows] = useState([]);
  const [allModuleRows, setAllModuleRows] = useState(new Map());
  const [dragging, setDragging] = useState(false);
  const allUsageRef = useRef([]);
  const fileInputRef = useRef(null);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('theme', theme);
  }, [theme]);

  const loadConfig = useCallback(async (file) => {
    try {
      setStatus(`Loading ${file.name}...`);
      const root = await readConfigAuto(file);
      const instanceLabels = buildInstanceLabels(root);
      if (!instanceLabels) {
        setStatus('No `instances` section found — is this a Companion export?');
        return;
      }

      const allUsage = scanUsage(root, instanceLabels);
      allUsageRef.current = allUsage;

      const usedIds = new Set(allUsage.map(u => u.moduleInstanceId));
      const entries = [...usedIds]
        .map(id => ({ id, label: instanceLabels.get(id) ?? id }))
        .map(e => ({ ...e, dropdownLabel: `${e.label} (${e.id})` }))
        .sort((a, b) => a.dropdownLabel.localeCompare(b.dropdownLabel, undefined, { sensitivity: 'base' }));

      setDropdownEntries(entries);
      setConfigName(file.name);

      const moduleRows = buildAllModuleRows(allUsage, entries, instanceLabels);
      setAllModuleRows(moduleRows);

      if (entries.length > 0) {
        setSelectedEntry(entries[0]);
        setTableRows(buildRowsForInstance(allUsage, entries[0].id));
        setStatus(`Loaded ${file.name} — ${entries.length} module${entries.length !== 1 ? 's' : ''} with actions. Selected: ${entries[0].dropdownLabel}`);
      } else {
        setSelectedEntry(null);
        setTableRows([]);
        setStatus(`Loaded ${file.name} — no module actions found.`);
      }
    } catch (err) {
      setStatus(`Failed to load: ${err.message}`);
    }
  }, []);

  const handleModuleChange = (e) => {
    const entry = dropdownEntries.find(en => en.dropdownLabel === e.target.value);
    if (!entry) return;
    setSelectedEntry(entry);
    setTableRows(buildRowsForInstance(allUsageRef.current, entry.id));
  };

  const handleDragOver = (e) => { e.preventDefault(); setDragging(true); };
  const handleDragLeave = (e) => { if (!e.currentTarget.contains(e.relatedTarget)) setDragging(false); };
  const handleDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) loadConfig(file);
  };

  const handleFileInput = (e) => {
    const file = e.target.files[0];
    if (file) loadConfig(file);
    e.target.value = '';
  };

  const hasContent = dropdownEntries.length > 0;

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {dragging && <div className="drag-overlay">Drop to open</div>}

      {/* Top bar */}
      <div className="top-bar">
        <img src={`${import.meta.env.BASE_URL}favicon.png`} alt="" style={{ height: 24, width: 24, flexShrink: 0 }} />
        <button onClick={() => fileInputRef.current.click()}>Open File</button>
        <input ref={fileInputRef} type="file" accept=".companionconfig,*" style={{ display: 'none' }} onChange={handleFileInput} />

        <label>Module:</label>
        <select
          disabled={!hasContent}
          value={selectedEntry?.dropdownLabel ?? ''}
          onChange={handleModuleChange}
        >
          {!hasContent && <option value="">Select a module...</option>}
          {dropdownEntries.map(e => (
            <option key={e.id} value={e.dropdownLabel}>{e.dropdownLabel}</option>
          ))}
        </select>

        <button
          disabled={!hasContent || tableRows.length === 0}
          onClick={() => exportCurrent(tableRows, configName, selectedEntry?.label ?? '')}
        >
          Export list
        </button>
        <button
          disabled={allModuleRows.size === 0}
          onClick={() => exportAll(allModuleRows, configName)}
        >
          Full export
        </button>

        <div className="spacer" />

        <div className="theme-toggle">
          <button className={theme === 'light' ? 'active' : ''} onClick={() => setTheme('light')}>&#9728;&#65038;</button>
          <button className={theme === 'dark' ? 'active' : ''} onClick={() => setTheme('dark')}>&#9790;</button>
        </div>
      </div>

      {/* Main content */}
      {hasContent
        ? <ResultTable rows={tableRows} />
        : (
          <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div className={`drop-target${dragging ? ' dragging' : ''}`}>
              <h2>Modulus for Companion</h2>
              <p>Drop a .companionconfig file here, or use Open File above.</p>
              <button onClick={() => fileInputRef.current.click()}>Open File</button>
            </div>
          </div>
        )
      }

      {/* Status bar */}
      <div className="status-bar">{status}</div>
    </div>
  );
}
