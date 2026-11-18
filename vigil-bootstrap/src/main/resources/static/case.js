const demo = new URLSearchParams(location.search).has('demo');
const fixture = {
  id: '7b4a8d2c-7fd7-4d1c-98ec-c067590709d4',
  status: 'OBSERVADO', source: 'pg_stat_statements', command: 'SELECT',
  observedAt: '2026-11-06T11:18:00-03:00',
  window: { start: '2026-11-06T11:17:00-03:00', end: '2026-11-06T11:18:00-03:00' },
  metrics: { calls: 10, totalExecutionMillis: 2000, meanExecutionMillis: 200, rows: 100 },
  explain: { estimated: true, text: 'Plano ainda não coletado. Uma análise EXPLAIN será apresentada aqui quando existir evidência.' },
  events: [{ type: 'Consulta descoberta', at: '2026-11-06T11:18:00-03:00', detail: 'Caso criado a partir da observação.' }]
};
const $ = id => document.getElementById(id);
const number = value => value == null ? '—' : new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value);
const date = value => value ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '—';
let caseData;
const statusLabels = {
  OBSERVADO: 'Em observação', OBSERVED: 'Em observação', DISCOVERED: 'Em observação', TRIAGED: 'Triado', EVIDENCE_COLLECTED: 'Evidência coletada',
  SANITIZED: 'Contexto seguro', DIAGNOSED: 'Diagnosticado', HYPOTHESIS_PROPOSED: 'Hipótese proposta',
  VALIDATING: 'Em validação', VALIDATED: 'Validado', INVALIDATED: 'Invalidado',
  INCONCLUSIVE: 'Inconclusivo', AWAITING_HUMAN: 'Aguardando decisão', APPROVED: 'Aprovado',
  REJECTED: 'Rejeitado', CHANGES_REQUESTED: 'Alterações solicitadas', ARCHIVED: 'Arquivado'
};
function updateDecisionPanel(data) {
  const status = data.status;
  const terminalStatus = ['APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'ARCHIVED'];
  // O fixture histórico usa OBSERVADO; o domínio atual usa DISCOVERED.
  // Qualquer estado que ainda não tenha sido triado inicia no mesmo passo de triagem.
  const awaitingTriage = !['TRIAGED', 'AWAITING_HUMAN', ...terminalStatus].includes(status);
  const triage = $('triage-form'), prepare = $('prepare-review'), decision = $('human-decision-form');
  const title = $('decision-heading'), copy = $('decision-copy'), state = $('decision-state');
  triage.hidden = !awaitingTriage;
  prepare.hidden = status !== 'TRIAGED';
  decision.hidden = status !== 'AWAITING_HUMAN';
  state.hidden = awaitingTriage || ['TRIAGED', 'AWAITING_HUMAN'].includes(status);
  if (awaitingTriage) {
    title.textContent = 'Triagem';
    copy.textContent = 'Registre o motivo da triagem. Nenhuma alteração é feita no banco monitorado.';
  } else if (status === 'TRIAGED') {
    title.textContent = 'Preparar curadoria';
    copy.textContent = 'O próximo passo monta a fixture sintética e auditável até a recomendação; o banco monitorado não é acessado.';
  } else if (status === 'AWAITING_HUMAN') {
    title.textContent = 'Registrar decisão';
    copy.textContent = 'A decisão será persistida na auditoria. Aprovar, rejeitar ou solicitar alterações não executa DDL ou DML.';
  } else {
    title.textContent = 'Decisão registrada';
    copy.textContent = 'A transição foi registrada na trilha de auditoria. Nenhuma alteração foi executada no banco monitorado.';
    state.textContent = `Estado atual: ${statusLabels[status] || status}.`;
  }
}
function render(data) {
  caseData = data;
  $('case-id').textContent = `ID ${data.id}`;
  $('case-source').textContent = data.source || '—';
  $('case-command').textContent = data.command || '—';
  $('case-observed').textContent = date(data.observedAt);
  $('case-status').textContent = statusLabels[data.status] || data.status;
  $('case-status').classList.toggle('triaged', ['TRIAGED', 'AWAITING_HUMAN', 'APPROVED'].includes(data.status));
  $('window-label').textContent = data.window ? `${date(data.window.start)} – ${date(data.window.end)}` : 'Janela indisponível';
  for (const [element, key] of [['metric-calls','calls'],['metric-total','totalExecutionMillis'],['metric-mean','meanExecutionMillis'],['metric-rows','rows']]) {
    $(element).textContent = number(data.metrics?.[key]);
  }
  $('explain-text').textContent = data.explain?.text || 'Nenhum plano anexado ao caso.';
  const list = $('timeline');
  list.replaceChildren();
  for (const event of data.events || []) {
    const item = document.createElement('li');
    const heading = document.createElement('strong');
    heading.textContent = ({ ANALYSIS_CASE_DISCOVERED: 'Consulta descoberta', QUERY_TRIAGED: 'Consulta triada' })[event.type] || event.type || 'Evento';
    const details = document.createElement('div'); details.textContent = event.detail || '';
    const when = document.createElement('time'); when.dateTime = event.at || ''; when.textContent = date(event.at);
    item.append(heading, details, when); list.append(item);
  }
  if (!list.children.length) { const item = document.createElement('li'); item.textContent = 'Ainda não há eventos registrados.'; list.append(item); }
  updateDecisionPanel(data);
  window.dispatchEvent(new CustomEvent('vigil:case-updated', { detail: data }));
}
async function load() {
  if (demo) {
    $('source-label').textContent = 'Dados demonstrativos';
    $('notice').textContent = 'Modo de demonstração: dados sintéticos. A triagem feita aqui não é persistida.';
    $('notice').classList.add('demo');
    render(structuredClone(fixture));
    return;
  }
  try {
    const response = await fetch('/api/cases/demo', { headers: { Accept: 'application/json' } });
    if (response.status === 404) {
      $('source-label').textContent = 'Caso ainda não criado';
      $('notice').textContent = 'Inicie uma avaliação local. Identidade e triagem serão persistidas; métricas iniciais são uma fixture de laboratório.';
      $('notice').classList.add('demo');
      $('create-case').hidden = false;
      $('case-status').textContent = 'Não iniciado';
      $('triage-form').hidden = true;
      $('prepare-review').hidden = true;
      $('human-decision-form').hidden = true;
      return;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    render(await response.json());
    $('source-label').textContent = 'Caso persistido · métricas de fixture';
    $('notice').textContent = 'Identidade e auditoria persistidas em SQLite. Métricas iniciais de fixture de laboratório; não são medições atuais do target.';
    $('notice').classList.add('demo');
  } catch {
    $('source-label').textContent = 'Sem conexão com a API';
    $('notice').textContent = 'A API do caso ainda não está disponível. Para visualizar o protótipo com dados sintéticos, abra esta página com ?demo=1.';
    $('notice').classList.add('error');
    $('triage-form').hidden = true;
    $('prepare-review').hidden = true;
    $('human-decision-form').hidden = true;
  }
}
$('create-case').addEventListener('click', async () => {
  $('create-case').disabled = true;
  $('notice').textContent = 'Criando caso…';
  try {
    const response = await fetch('/api/cases/demo', { method: 'POST', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    $('create-case').hidden = true;
    render(await response.json());
    $('source-label').textContent = 'Caso persistido · métricas de fixture';
    $('notice').textContent = 'Caso criado em SQLite. Métricas iniciais de fixture de laboratório; não são medições atuais do target.';
  } catch {
    $('notice').textContent = 'Não foi possível criar o caso. Verifique o serviço local e tente novamente.';
    $('notice').classList.add('error');
    $('create-case').disabled = false;
  }
});
$('triage-form').addEventListener('submit', async event => {
  event.preventDefault();
  const reason = $('reason').value.trim();
  const feedback = $('form-feedback');
  feedback.classList.remove('ok');
  if (reason.length < 8) { feedback.textContent = 'Informe uma justificativa com pelo menos 8 caracteres.'; $('reason').focus(); return; }
  $('triage-button').disabled = true;
  feedback.textContent = 'Registrando triagem…';
  try {
    if (demo) {
      const now = new Date().toISOString();
      caseData = { ...caseData, status: 'TRIAGED', events: [...caseData.events, { type: 'Triagem simulada', at: now, detail: reason }] };
      render(caseData);
      feedback.textContent = 'Triagem simulada. Os dados não foram salvos.';
    } else {
      const response = await fetch(`/api/cases/${encodeURIComponent(caseData.id)}/triage`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ reason })
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
      feedback.textContent = 'Triagem registrada e incluída na auditoria.';
    }
    feedback.classList.add('ok');
  } catch {
    feedback.textContent = 'Não foi possível registrar a triagem. Tente novamente.';
    $('triage-button').disabled = false;
  }
});
$('prepare-review').addEventListener('click', async () => {
  const button = $('prepare-review');
  button.disabled = true;
  $('form-feedback').textContent = 'Preparando curadoria…';
  try {
    if (demo) {
      const now = new Date().toISOString();
      caseData = { ...caseData, status: 'AWAITING_HUMAN', events: [...caseData.events,
        { type: 'Evidência sintética vinculada', at: now, detail: 'Fixture de laboratório para demonstração.' },
        { type: 'Transição: AWAITING_HUMAN', at: now, detail: 'Recomendação pronta para curadoria humana.' }] };
      render(caseData);
      $('decision-feedback').textContent = 'Curadoria simulada pronta. Os dados não foram salvos.';
    } else {
      const response = await fetch(`/api/cases/${encodeURIComponent(caseData.id)}/prepare-human-review`, {
        method: 'POST', headers: { Accept: 'application/json' }
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
      $('decision-feedback').textContent = 'Caso preparado para decisão humana e persistido na auditoria.';
    }
    $('decision-feedback').classList.add('ok');
  } catch {
    $('form-feedback').textContent = 'Não foi possível preparar a curadoria. Tente novamente.';
    button.disabled = false;
  }
});
$('human-decision-form').addEventListener('submit', async event => {
  event.preventDefault();
  const selected = document.querySelector('input[name="decision"]:checked');
  const reason = $('decision-reason').value.trim();
  const feedback = $('decision-feedback');
  feedback.classList.remove('ok');
  if (!selected) { feedback.textContent = 'Escolha aprovar, rejeitar ou solicitar alterações.'; return; }
  if (reason.length < 8) { feedback.textContent = 'Informe uma justificativa com pelo menos 8 caracteres.'; $('decision-reason').focus(); return; }
  $('decision-button').disabled = true;
  feedback.textContent = 'Registrando decisão…';
  try {
    if (demo) {
      const now = new Date().toISOString();
      const nextStatus = { APPROVE: 'APPROVED', REJECT: 'REJECTED', REQUEST_CHANGES: 'CHANGES_REQUESTED' }[selected.value];
      caseData = { ...caseData, status: nextStatus, events: [...caseData.events,
        { type: `Transição: ${nextStatus}`, at: now, detail: reason }] };
      render(caseData);
      $('decision-state').textContent = 'Decisão simulada. Os dados não foram salvos.';
      $('decision-state').hidden = false;
    } else {
      const response = await fetch(`/api/cases/${encodeURIComponent(caseData.id)}/decision`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ decision: selected.value, reason })
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
    }
    feedback.textContent = 'Decisão registrada e incluída na auditoria.';
    feedback.classList.add('ok');
  } catch {
    feedback.textContent = 'Não foi possível registrar a decisão. Revise o estado do caso e tente novamente.';
    $('decision-button').disabled = false;
  }
});
load();
