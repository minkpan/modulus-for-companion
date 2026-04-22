function firstNonBlank(...vals) {
  for (const v of vals) if (v != null && String(v).trim() !== '') return String(v).trim();
  return null;
}

function coerceEntries(v) {
  if (Array.isArray(v)) return v.map((el, i) => [String(i), el]);
  if (v && typeof v === 'object') return Object.entries(v);
  return [];
}

function resolveInstanceId(action) {
  return firstNonBlank(
    action.connectionId,
    action.instance,
    action.instance_id,
    action.options?.instance_id,
    action.options?.instanceId,
    action.options?.connectionId,
  );
}

function resolveDefId(action) {
  return firstNonBlank(
    action.definitionId,
    action.definition_id,
    action.actionId,
    action.action_id,
    action.action,
    action.id,
  );
}

function collectActionLists(stepVal) {
  if (!stepVal || typeof stepVal !== 'object') return [];
  const result = [];

  if (stepVal.action_sets && typeof stepVal.action_sets === 'object' && !Array.isArray(stepVal.action_sets)) {
    for (const v of Object.values(stepVal.action_sets)) {
      if (Array.isArray(v)) result.push(v);
    }
  }

  if (Array.isArray(stepVal.actions)) {
    result.push(stepVal.actions);
  } else if (stepVal.actions && typeof stepVal.actions === 'object') {
    for (const v of Object.values(stepVal.actions)) {
      if (Array.isArray(v)) result.push(v);
    }
  }

  if (result.length === 0) {
    for (const v of Object.values(stepVal)) {
      if (Array.isArray(v)) result.push(v);
    }
  }

  return result;
}

// Recursively collect all actions from an action list, diving into action_group children
function* walkActions(actions) {
  if (!Array.isArray(actions)) return;
  for (const action of actions) {
    if (!action || typeof action !== 'object') continue;
    yield action;
    // action_group: children is { groupKey: action[] }
    if (action.children && typeof action.children === 'object') {
      for (const childList of Object.values(action.children)) {
        yield* walkActions(childList);
      }
    }
  }
}

function emitActionsFromControls(controls, pageNum, instanceLabels, out) {
  for (const [rowKey, rowVal] of coerceEntries(controls)) {
    const rowIndex = parseInt(rowKey, 10);
    for (const [colKey, colVal] of coerceEntries(rowVal)) {
      const colIndex = parseInt(colKey, 10);
      if (!colVal || typeof colVal !== 'object') continue;

      const steps = colVal.steps;
      if (steps == null) continue;

      for (const [stepKey, stepVal] of coerceEntries(steps)) {
        const stepIndex = parseInt(stepKey, 10);
        const actionLists = collectActionLists(stepVal);

        for (const actions of actionLists) {
          for (const action of walkActions(actions)) {
            if (action.type != null && action.type !== 'action') continue;

            const instanceId = resolveInstanceId(action);
            if (!instanceId || !instanceLabels.has(instanceId)) continue;

            const defId = resolveDefId(action);
            const buttonId = `${rowIndex + 1}.${colIndex + 1}`;

            out.push({
              moduleInstanceId: instanceId,
              page: pageNum,
              step: stepIndex + 1,
              actionDefId: defId,
              buttonIdentity: buttonId,
              buttonDisplay: buttonId,
            });
          }
        }
      }
    }
  }
}

function scanUsageSinglePage(root, instanceLabels) {
  const out = [];
  const page = root.page;
  if (!page || typeof page !== 'object') return out;
  const controls = page.controls;
  if (!controls) return out;
  const pageNum = root.oldPageNumber ?? 1;
  emitActionsFromControls(controls, pageNum, instanceLabels, out);
  return out;
}

function scanUsageNew(root, instanceLabels) {
  const out = [];
  const pagesVal = root.pages;
  if (pagesVal == null) return out;

  for (const [pageKey, pageVal] of coerceEntries(pagesVal)) {
    const pageNum = parseInt(pageKey, 10);
    if (!pageVal || typeof pageVal !== 'object') continue;
    const controls = pageVal.controls;
    if (controls == null) continue;
    emitActionsFromControls(controls, pageNum, instanceLabels, out);
  }
  return out;
}

function scanUsageLegacy(root, instanceLabels) {
  const out = [];
  const buckets = ['actions', 'release_actions', 'rotate_left_actions', 'rotate_right_actions'];

  for (const bucket of buckets) {
    const byPage = root[bucket];
    if (!byPage || typeof byPage !== 'object' || Array.isArray(byPage)) continue;

    for (const [pageKey, byPos] of Object.entries(byPage)) {
      const pageNum = parseInt(pageKey, 10);
      if (!byPos || typeof byPos !== 'object') continue;

      for (const [posKey, posVal] of Object.entries(byPos)) {
        const posIndex = parseInt(posKey, 10);
        if (posIndex < 1) continue;

        const actionLists = [];
        if (Array.isArray(posVal)) {
          actionLists.push(posVal);
        } else if (posVal && typeof posVal === 'object') {
          for (const v of Object.values(posVal)) {
            if (Array.isArray(v)) actionLists.push(v);
          }
        } else {
          continue;
        }

        let stepBase = 0;
        for (const list of actionLists) {
          list.forEach((act, i) => {
            if (!act || typeof act !== 'object') return;
            const instanceId = firstNonBlank(
              act.instance,
              act.connectionId,
              act.connection_id,
              act.options?.instance_id,
              act.options?.instanceId,
              act.options?.connectionId,
            );
            if (!instanceId || !instanceLabels.has(instanceId)) return;

            const defId = firstNonBlank(act.action, act.definitionId, act.actionId, act.id);
            const step = stepBase + i + 1;
            const idStr = String(posIndex);

            out.push({
              moduleInstanceId: instanceId,
              page: pageNum,
              step,
              actionDefId: defId,
              buttonIdentity: idStr,
              buttonDisplay: idStr,
            });
          });
          stepBase += list.length;
        }
      }
    }
  }
  return out;
}

export function scanUsage(root, instanceLabels) {
  // Single-page export: has root.page (singular) and type === 'page'
  if (root.type === 'page' && root.page && typeof root.page === 'object') {
    return scanUsageSinglePage(root, instanceLabels);
  }
  // Full config new format
  const pagesVal = root.pages;
  if (pagesVal != null && (Array.isArray(pagesVal) || typeof pagesVal === 'object')) {
    return scanUsageNew(root, instanceLabels);
  }
  // Legacy format
  const actionsVal = root.actions;
  if (actionsVal != null && typeof actionsVal === 'object' && !Array.isArray(actionsVal)) {
    return scanUsageLegacy(root, instanceLabels);
  }
  return [];
}

export function buildInstanceLabels(root) {
  const instances = root.instances;
  if (!instances || typeof instances !== 'object') return null;
  const labels = new Map();
  for (const [id, val] of Object.entries(instances)) {
    if (!id) continue;
    const label = val?.label != null ? String(val.label) : id;
    labels.set(id, label);
  }
  return labels;
}
