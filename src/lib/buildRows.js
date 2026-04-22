function buttonComparator(a, b) {
  const aDot = a.includes('.');
  const bDot = b.includes('.');
  if (aDot && bDot) {
    const [ar, ac] = a.split('.').map(Number);
    const [br, bc] = b.split('.').map(Number);
    return ar !== br ? ar - br : ac - bc;
  }
  if (!aDot && !bDot) {
    const an = parseInt(a, 10), bn = parseInt(b, 10);
    if (!isNaN(an) && !isNaN(bn)) return an - bn;
    return a.localeCompare(b, undefined, { sensitivity: 'base' });
  }
  return aDot ? 1 : -1;
}

export function buildRowsForInstance(allUsage, instanceId) {
  const byPage = new Map();

  for (const u of allUsage) {
    if (u.moduleInstanceId !== instanceId) continue;
    if (!byPage.has(u.page)) byPage.set(u.page, new Map());
    const pageMap = byPage.get(u.page);
    if (!pageMap.has(u.buttonIdentity)) {
      pageMap.set(u.buttonIdentity, { steps: new Set(), actions: [], actionsSet: new Set(), display: u.buttonDisplay });
    }
    const agg = pageMap.get(u.buttonIdentity);
    agg.steps.add(u.step);
    if (u.actionDefId && u.actionDefId.trim() !== '' && !agg.actionsSet.has(u.actionDefId.trim())) {
      agg.actionsSet.add(u.actionDefId.trim());
      agg.actions.push(u.actionDefId.trim());
    }
  }

  const rows = [];
  const sortedPages = [...byPage.keys()].sort((a, b) => a - b);

  for (const pageNum of sortedPages) {
    rows.push({ page: String(pageNum), button: '', action: '', isHeader: true });
    const pageMap = byPage.get(pageNum);
    const sortedButtons = [...pageMap.keys()].sort(buttonComparator);

    for (const buttonId of sortedButtons) {
      const agg = pageMap.get(buttonId);
      const stepsArr = [...agg.steps].sort((a, b) => a - b);
      const stepText = stepsArr.length === 1
        ? `(step ${stepsArr[0]})`
        : `(steps ${stepsArr.join(', ')})`;
      const actionText = agg.actions.join(', ');
      rows.push({ page: '', button: `${agg.display} ${stepText}`, action: actionText, isHeader: false });
    }
  }

  return rows;
}

export function buildAllModuleRows(allUsage, dropdownEntries, instanceLabels) {
  const allModuleRows = new Map();
  for (const { label, id } of dropdownEntries) {
    allModuleRows.set(label, buildRowsForInstance(allUsage, id));
  }
  return allModuleRows;
}
