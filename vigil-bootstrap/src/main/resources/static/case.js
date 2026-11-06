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
function render(data) {
  caseData = data;
  $('case-id').textContent = `ID ${data.id}`;
  $('case-source').textContent = data.source || '—';
  $('case-command').textContent = data.command || '—';
  $('case-observed').textContent = date(data.observedAt);
  $('case-status').textContent = data.status === 'TRIAGED' ? 'Triado' : 'Em observação';
  $('case-status').classList.toggle('triaged', data.status === 'TRIAGED');
  $('window-label').textContent = data.window ? `${date(data.window.start)} – ${date(data.window.end)}` : 'Janela indisponível';
  for (const [element, key] of [['metric-calls','calls'],['metric-total','totalExecutionMillis'],['metric-mean','meanExecutionMillis'],['metric-rows','rows']]) {
    $(element).textContent = number(data.metrics?.[key]);
  }
  $('explain-text').textContent = data.explain?.text || 'Nenhum plano anexado ao caso.';
  const list = $('timeline');
  list.replaceChildren();
  for (const event of data.events || []) {
    const item = document.createElement('li');
    const heading = document.createElement('strong'); heading.textContent = event.type || 'Evento';
    const details = document.createElement('div'); details.textContent = event.detail || '';
    const when = document.createElement('time'); when.dateTime = event.at || ''; when.textContent = date(event.at);
    item.append(heading, details, when); list.append(item);
  }
  if (!list.children.length) { const item = document.createElement('li'); item.textContent = 'Ainda não há eventos registrados.'; list.append(item); }
  $('triage-button').disabled = data.status === 'TRIAGED';
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
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    render(await response.json());
    $('source-label').textContent = 'Dados persistidos';
    $('notice').textContent = 'Dados lidos do caso persistido. A triagem exige uma justificativa.';
  } catch {
    $('source-label').textContent = 'Sem conexão com a API';
    $('notice').textContent = 'A API do caso ainda não está disponível. Para visualizar o protótipo com dados sintéticos, abra esta página com ?demo=1.';
    $('notice').classList.add('error');
    $('triage-button').disabled = true;
  }
}
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
load();
