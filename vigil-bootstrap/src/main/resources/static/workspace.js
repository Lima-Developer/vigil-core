(() => {
  const $ = selector => document.querySelector(selector);
  const escape = value => String(value ?? '').replace(/[&<>"']/g, char =>
    ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[char]));
  const number = (value, digits = 2) => value == null ? '—' :
    new Intl.NumberFormat('pt-BR', {maximumFractionDigits:digits}).format(value);
  const additionalExecutions = () => curation?.additionalExecutions || [];
  const claudeExecutions = () => additionalExecutions().filter(item => item.provider === 'anthropic-claude-code-cli');
  const claudeExecution = () => claudeExecutions().at(-1);
  const claudeDecision = queryId => claudeExecution()?.decisions?.find(item => item.queryId === queryId);
  const qwenExecutions = () => additionalExecutions().filter(item => item.provider === 'openrouter');
  const qwenExecution = () => qwenExecutions().at(-1);
  const qwenDecision = queryId => qwenExecution()?.decisions?.find(item => item.queryId === queryId);
  const modelLabel = execution => execution?.provider === 'openrouter' ? 'Qwen 3.8 27B · OpenRouter' : execution?.model || 'Modelo externo';
  const icons = {
    overview:'<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
    telemetria:'<path d="M3 13h4l3-8 4 14 3-8h4"/>',
    caso:'<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/>',
    auditoria:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    consumo:'<path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z"/>',
    roteiro:'<path d="M5 4v16M5 5h14l-3 4 3 4H5"/>',
    theme:'<path d="M20 15a9 9 0 0 1-11-11A9 9 0 1 0 20 15Z"/>',
    menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',
    collapse:'<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
    database:'<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>'
  };
  const icon = key => '<svg viewBox="0 0 24 24" aria-hidden="true">' + icons[key] + '</svg>';
  const screens = {
    overview:['Visão geral','Panorama do laboratório','Evidências medidas, cobertura do acervo e próximos passos.'],
    telemetria:['Telemetria','Saúde do banco','Chamadas SQL, latência, sessões e capacidade de armazenamento.'],
    caso:['Casos','Consultas em análise','Explore o SQL, os planos e os resultados de cada cenário complexo.'],
    auditoria:['Auditoria','Trilha de evidências','Eventos do caso e registros das execuções do laboratório.'],
    consumo:['Uso de IA','Consumo e validação de IA','Tokens, custos e fases de execução por consulta.'],
    roteiro:['Roteiro','Caminho até a defesa','Entregas concluídas, próximas atividades e critérios de conclusão.']
  };
  const navLink = (key, compact = false) => '<a href="#'+key+'" class="'+(compact?'segment':'rail-link')+'" title="'+screens[key][0]+'">'+icon(key)+'<span>'+screens[key][0]+'</span></a>';
  const navigation = () => '<p class="rail-label">WORKSPACE</p><nav aria-label="Navegação principal">'+
    ['overview','telemetria','caso'].map(k=>navLink(k)).join('')+'</nav><p class="rail-label">GOVERNANÇA</p><nav aria-label="Governança">'+
    ['auditoria','consumo','roteiro'].map(k=>navLink(k)).join('')+'</nav>';
  const sidebar = $('.sidebar');
  sidebar.innerHTML = '<div class="rail-brand"><a href="#overview" aria-label="Vigil, visão geral"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M5 6h22L16 27 5 6Z"/><path d="m11 12 5 9 5-9"/></svg><strong>Vigil</strong></a><button class="icon-button" id="collapse-nav" aria-label="Recolher navegação" aria-expanded="true">'+icon('collapse')+'</button></div>'+
    navigation()+'<div class="rail-footer"><div class="lab-label">'+icon('database')+'<span>Laboratório local<small>Ambiente isolado</small></span></div><button class="theme-button" data-theme-toggle>'+icon('theme')+'<span>Alterar tema</span></button></div>';
  const caseSourceLabel=$('#source-label');
  $('.topbar').remove();
  const main = $('.main');
  main.removeAttribute('id');
  const caseContent = $('.content');
  caseContent.prepend(caseSourceLabel);
  caseContent.classList.add('legacy-case');
  caseContent.querySelectorAll('#telemetria,#auditoria').forEach(el=>el.removeAttribute('id'));
  const header = document.createElement('header');
  header.className = 'workspace-header';
  header.innerHTML = '<button id="open-nav" class="icon-button mobile-menu" aria-label="Abrir navegação" aria-haspopup="dialog">'+icon('menu')+'</button><span class="header-label">Workspace</span><nav class="segmented-nav" aria-label="Abas do workspace">'+Object.keys(screens).map(k=>navLink(k,true)).join('')+'</nav><button class="icon-button" data-theme-toggle aria-label="Alterar tema">'+icon('theme')+'</button>';
  main.prepend(header);
  const dialog = document.createElement('dialog');
  dialog.className = 'mobile-drawer';
  dialog.setAttribute('aria-label','Navegação do workspace');
  dialog.innerHTML = '<div class="drawer-heading"><strong>Vigil</strong><button class="icon-button" aria-label="Fechar navegação">×</button></div>'+navigation()+'<button class="theme-button" data-theme-toggle>'+icon('theme')+'<span>Alterar tema</span></button>';
  document.body.append(dialog);
  $('#open-nav').onclick = () => dialog.showModal();
  dialog.querySelector('button').onclick = () => dialog.close();
  dialog.addEventListener('click', e => { if (e.target === dialog || e.target.closest('a')) dialog.close(); });
  $('#collapse-nav').onclick = () => {
    const collapsed = document.body.classList.toggle('rail-collapsed');
    $('#collapse-nav').setAttribute('aria-expanded', String(!collapsed));
    $('#collapse-nav').setAttribute('aria-label', collapsed?'Expandir navegação':'Recolher navegação');
    redrawCharts();
  };
  const workspace = document.createElement('div');
  workspace.className = 'workspace';
  workspace.innerHTML = '<div class="page-heading"><div><p class="eyebrow">VIGIL / WORKSPACE</p><h1 id="workspace-title"></h1><p id="workspace-description"></p></div><span class="source-badge">LABORATÓRIO · DADOS SINTÉTICOS</span></div>'+
    Object.keys(screens).map(k=>'<section class="screen" data-screen="'+k+'" aria-label="'+screens[k][0]+'" hidden></section>').join('');
  main.append(workspace);
  let corpus = null, corpusError = false, curation = null, curationError = false;
  let selectedId = 'Q01', currentScreen = 'overview';
  let preview = new URLSearchParams(location.search).has('demo');
  let caseEvents = null;
  let localObservation = null, telemetrySnapshot = null, telemetryFailure = false, telemetryPaused = false;
  let telemetryAbort = null, telemetryTimer = null;
  const localSamples = [];
  const media = matchMedia('(prefers-color-scheme: dark)');
  let preference;
  try { preference = localStorage.getItem('vigil-theme'); } catch {}
  function theme(value) {
    document.documentElement.dataset.theme = value;
    document.querySelectorAll('[data-theme-toggle]').forEach(button=>{
      button.setAttribute('aria-label', value==='dark'?'Ativar tema claro':'Ativar tema escuro');
      button.setAttribute('aria-pressed', String(value==='dark'));
    });
  }
  theme(preference || (media.matches?'dark':'light'));
  document.querySelectorAll('[data-theme-toggle]').forEach(button=>button.onclick=()=>{
    preference = document.documentElement.dataset.theme==='dark'?'light':'dark';
    theme(preference);
    try { localStorage.setItem('vigil-theme',preference); } catch {}
  });
  media.addEventListener('change',e=>{ if(!preference) theme(e.matches?'dark':'light'); });
  const section = key => $('[data-screen="'+key+'"]');
  const badge = (label,tone='') => '<span class="badge '+tone+'">'+escape(label)+'</span>';
  const card = (title,body,extra='') => '<article class="dash-card '+extra+'"><h2>'+title+'</h2>'+body+'</article>';
  const kpi = (label,value,note,tone='') => '<article class="kpi-card"><span>'+label+'</span><strong>'+value+'</strong><small class="'+tone+'">'+note+'</small></article>';
  const empty = text => '<div class="empty-state">'+icon('database')+'<strong>'+text+'</strong><p>Nenhum valor estimado substitui uma medição ausente.</p></div>';
  const measuredNote = '<p class="evidence-note">PostgreSQL 16.4 · dataset sintético em escala 3 · cache aquecido · 7 repetições por variante. Resultados exploratórios, sem ganho garantido.</p>';
  const cases = () => corpus?.cases || [];
  const change = q => (q.variants.original.median_execution_ms-q.variants.candidate.median_execution_ms)/q.variants.original.median_execution_ms*100;
  function chartSlot(id,label) { return '<div class="chart-slot" data-plot="'+id+'" aria-label="'+label+'"></div>'; }
  function renderOverview() {
    const root = section('overview');
    if(!corpus) { root.innerHTML=empty(corpusError?'Não foi possível carregar as evidências':'Carregando evidências do laboratório'); return; }
    const improved=cases().filter(q=>!q.control&&change(q)>0).length;
    const regressed=cases().filter(q=>!q.control&&change(q)<=0).length;
    root.innerHTML='<div class="overview-layout"><div class="overview-main"><div class="kpi-grid">'+
      kpi('Queries complexas',cases().length,'Acervo versionado · 10 cenários')+
      kpi('Equivalência nos dois seeds','20 / 20','Multiconjuntos e casos-limite','positive')+
      kpi('Planos medidos',number(corpus.planCount,0),'Dois seeds · 7 repetições')+'</div>'+
      card('Tempo por consulta <span class="subtle">· mediana em ms</span>','<div class="legend"><span>Original</span><span>Candidata manual</span></div>'+chartSlot('comparison','Tempos medidos de cada consulta')+measuredNote,'main-chart')+
      '<div class="bottom-cards">'+card('Resultados observados','<ul class="metric-list"><li>Melhorias preliminares <strong class="positive">'+improved+'</strong></li><li>Regressões <strong class="negative">'+regressed+'</strong></li><li>Controle sem alteração <strong>1</strong></li></ul><a class="text-link" href="#caso">Revisar cada resultado →</a>')+
      card('Ambiente do experimento','<ul class="metric-list"><li>Banco <strong>PostgreSQL 16.4</strong></li><li>CPU / memória <strong>2 / 1 GiB</strong></li><li>Escala / distribuições <strong>'+number(corpus.scale,0)+' · seeds 42 e 43</strong></li></ul>')+
      card('Próxima entrega','<p class="card-copy">Registrar o parecer humano do caso escolhido e ensaiar o roteiro completo da defesa.</p><a class="text-link" href="#roteiro">Acompanhar roteiro →</a>')+'</div></div>'+
      card('Execuções do acervo <span class="subtle">Histórico</span>','<p class="caption">Seed 42 · '+escape(new Date(corpus.observedAt).toLocaleDateString('pt-BR'))+' · '+number(corpus.repetitions,0)+' repetições</p><ul class="activity-list">'+cases().map(q=>'<li><a href="#caso/'+q.id+'"><span class="activity-dot"></span><span><strong>'+q.id+'</strong><small>Equivalência validada</small></span><span class="activity-time">'+number(q.variants.candidate.median_execution_ms)+' ms<small>Candidata</small></span></a></li>').join('')+'</ul>','activity-card')+'</div>';
  }
  function renderTelemetry() {
    section('telemetria').innerHTML='<div class="view-toolbar"><span class="subtle">Métricas operacionais do target</span><button id="preview-toggle" class="secondary-button" aria-pressed="'+preview+'">'+(preview?'Sair da prévia visual':'Ativar prévia visual')+'</button></div><div id="collector-notice" class="notice '+(preview?'demo':'')+'" role="status"></div><div class="kpi-grid four">'+
      kpi('Chamadas SQL','<span id="sql-calls-value">'+(preview?'67.589':'—')+'</span>','<span id="sql-calls-note">'+(preview?'Janela sintética de 30 min':'Aguardando amostra')+'</span>')+
      kpi('Latência média','<span id="mean-latency-value">'+(preview?'22 ms':'—')+'</span>','<span id="mean-latency-note">'+(preview?'Ponderada por chamadas':'Aguardando amostra')+'</span>')+
      kpi('Sessões em espera','<span id="waiting-sessions-value">'+(preview?'4':'—')+'</span>','<span id="waiting-sessions-note">'+(preview?'Série sintética':'Aguardando amostra')+'</span>')+
      kpi('Armazenamento local','<span id="local-space-value">'+(preview?'78,2 GiB':'—')+'</span>','<span id="local-space-origin">Volume do Vigil, não do target</span>')+'</div><div class="telemetry-controls" '+(preview?'hidden':'')+'><button id="pause-telemetry" class="secondary-button" aria-pressed="'+telemetryPaused+'">'+(telemetryPaused?'Retomar atualização':'Pausar atualização')+'</button><span id="telemetry-read-status" role="status">Leitura local a cada 30 s enquanto esta aba estiver visível.</span></div><div class="telemetry-grid">'+
      card('Atividade do banco <span class="subtle">· chamadas por intervalo</span>',chartSlot('calls','Chamadas SQL observadas por intervalo'))+
      card('Latência média <span class="subtle">· ms</span>',chartSlot('latency','Latência média ponderada por intervalo'))+
      card('Sessões <span class="subtle">· ativas e em espera</span>',chartSlot('sessions','Sessões agregadas observadas'))+
      card('Espaço local disponível <span class="subtle">· GiB</span>',chartSlot('storage',preview?'Espaço ilustrativo':'Leituras reais do espaço local'))+'</div><p class="evidence-note">A primeira amostra, resets e falhas terão estados próprios. freeLocalStorage de um provedor de banco exige uma fonte específica; não é o espaço livre deste computador.</p>';
    $('#preview-toggle').onclick=()=>{preview=!preview;renderTelemetry();redrawCharts();startTelemetry();};
    $('#pause-telemetry').onclick=async()=>{
      const nextPaused=!telemetryPaused;
      if(telemetrySnapshot?.collector?.enabled){
        $('#pause-telemetry').disabled=true;
        try {
          const response=await fetch('/api/workspace/telemetry/'+(nextPaused?'pause':'resume'),{method:'POST',cache:'no-store'});
          if(!response.ok)throw new Error('Collector control unavailable');
          telemetrySnapshot.collector=await response.json();
          telemetryPaused=Boolean(telemetrySnapshot.collector.paused);
        } catch { telemetryFailure=true; }
        $('#pause-telemetry').disabled=false;
      } else telemetryPaused=nextPaused;
      updateTelemetryRead();startTelemetry();
    };
    updateTelemetryRead();
  }
  function metricText(metric,suffix='') {
    return metric?.state==='available'&&typeof metric.value==='number'?number(metric.value)+(suffix?' '+suffix:''):'—';
  }
  function metricNote(metric,label) {
    if(!metric)return 'Aguardando amostra';
    if(metric.state==='available')return label+' · '+metric.quality+' · '+new Date(metric.observedAt).toLocaleTimeString('pt-BR');
    const states={warming_up:'Aquecendo baseline',unsupported:'Fonte não configurada',error:'Falha de leitura',stale:'Último valor conhecido'};
    return (states[metric.state]||'Indisponível')+(metric.reason?' · '+metric.reason:'');
  }
  function updateTelemetryRead() {
    if(preview){
      $('#collector-notice').textContent='Prévia visual: séries sintéticas para conferir o layout. Não representam o target.';
      return;
    }
    const metrics=telemetrySnapshot?.metrics||{};
    const available=localObservation?.state==='available';
    $('#sql-calls-value').textContent=metricText(metrics.sqlCalls);
    $('#sql-calls-note').textContent=metricNote(metrics.sqlCalls,'Delta de execuções');
    $('#mean-latency-value').textContent=metricText(metrics.meanExecutionMillis,'ms');
    $('#mean-latency-note').textContent=metricNote(metrics.meanExecutionMillis,'Ponderada por chamadas');
    $('#waiting-sessions-value').textContent=metricText(metrics.waitingSessions);
    $('#waiting-sessions-note').textContent=metricNote(metrics.waitingSessions,'Gauge de espera');
    $('#local-space-value').textContent=available?number(localObservation.value/1073741824)+' GiB':'—';
    $('#local-space-origin').textContent=available?'Medição real · volume local do Vigil':'Volume local · leitura indisponível';
    const collector=telemetrySnapshot?.collector;
    $('#collector-notice').textContent=collector?.enabled?
      collector.paused?'Coletor PostgreSQL pausado. O histórico permanece disponível.':
      collector.consecutiveFailures?'Coletor em backoff após '+collector.consecutiveFailures+' falha(s). Nenhum zero foi inventado.':
      collector.collecting?'Coleta serial em andamento.':'Coletor PostgreSQL ativo · deltas e gauges persistidos no SQLite local.':
      'Coletor PostgreSQL desabilitado. O volume local continua medido; métricas do target permanecem explicitamente indisponíveis.';
    $('#pause-telemetry').setAttribute('aria-pressed',String(telemetryPaused));
    $('#pause-telemetry').textContent=telemetryPaused?'Retomar atualização':'Pausar atualização';
    $('#telemetry-read-status').textContent=telemetryPaused?'Atualização pausada.':
      telemetryFailure?'Leitura indisponível. Nova tentativa em 30 s.':
      available?'Última leitura real: '+new Date(localObservation.observedAt).toLocaleTimeString('pt-BR')+' · atualização a cada 30 s.':
      'Aguardando leitura do volume local.';
  }
  function stopTelemetry() {
    clearTimeout(telemetryTimer);
    telemetryAbort?.abort();
    telemetryAbort=null;
  }
  function startTelemetry() {
    stopTelemetry();
    if(currentScreen!=='telemetria'||preview||telemetryPaused||document.hidden) return;
    const controller=new AbortController();
    telemetryAbort=controller;
    const timeout=setTimeout(()=>controller.abort(),8000);
    fetch('/api/workspace/telemetry',{signal:controller.signal,cache:'no-store'})
      .then(response=>{if(!response.ok)throw new Error('Telemetry unavailable');return response.json();})
      .then(data=>{
        if(controller!==telemetryAbort) return;
        const metric=data.metrics?.localUsableBytes;
        if(data.schemaVersion!==1||!metric||metric.source!=='filesystem'||!Array.isArray(data.samples)||!data.collector)throw new Error('Invalid telemetry');
        telemetrySnapshot=data;
        if(data.collector.enabled)telemetryPaused=Boolean(data.collector.paused);
        localObservation=metric;telemetryFailure=metric.state==='error';
        if(metric.state==='available'&&typeof metric.value==='number'&&metric.observedAt){
          localSamples.push({at:metric.observedAt,value:metric.value/1073741824});
          if(localSamples.length>60)localSamples.shift();
        }
        updateTelemetryRead();redrawCharts();
      }).catch(()=>{
        if(controller!==telemetryAbort)return;
        localObservation=null;telemetryFailure=true;updateTelemetryRead();
      }).finally(()=>{
        clearTimeout(timeout);
        if(controller===telemetryAbort){
          telemetryAbort=null;
          telemetryTimer=setTimeout(startTelemetry,30000);
        }
      });
  }
  document.addEventListener('visibilitychange',startTelemetry);
  function renderCases() {
    const root=section('caso');
    root.innerHTML='<div class="view-toolbar"><span class="subtle">Acervo de queries complexas</span><label class="search-field">'+icon('caso')+'<input id="case-search" type="search" aria-label="Filtrar queries" placeholder="Filtrar por ID ou cenário"></label></div><div class="case-layout"><div class="dash-card case-list" aria-label="Queries do acervo"></div><div class="case-detail"></div></div><details class="manual-case"><summary>Caso de triagem local · identidade e histórico persistidos fora do modo demo</summary><div id="manual-case-host"></div></details>';
    $('#manual-case-host').append(caseContent);
    $('#case-search').oninput=renderCaseList;
    renderCaseList();
    renderCaseDetail();
  }
  function renderCaseList() {
    const term=$('#case-search').value.toLocaleLowerCase('pt-BR');
    const matches=cases().filter(q=>(q.id+' '+q.title).toLocaleLowerCase('pt-BR').includes(term));
    $('.case-list').innerHTML=matches.length?matches.map(q=>'<a href="#caso/'+q.id+'" class="case-row '+(q.id===selectedId?'selected':'')+'" '+(q.id===selectedId?'aria-current="true"':'')+'><span>'+q.id+' '+badge(q.control?'Controle':change(q)>0?'Melhoria':'Regressão',q.control?'':change(q)>0?'positive':'negative')+'</span><strong>'+escape(q.title)+'</strong><small>'+number(q.variants.original.median_execution_ms)+' → '+number(q.variants.candidate.median_execution_ms)+' ms</small></a>').join(''):'<p class="card-copy">Nenhuma query encontrada.</p>';
  }
  function planTree(node) {
    return '<li><span>'+escape(node['Node Type'])+'</span><small>'+number(node['Actual Rows'],0)+' linhas/loop · '+number(node['Actual Loops'],0)+' loops · custo estimado '+number(node['Total Cost'])+'</small>'+(node.Plans?'<ul>'+node.Plans.map(planTree).join('')+'</ul>':'')+'</li>';
  }
  function renderCaseDetail() {
    const root=$('.case-detail'), q=cases().find(item=>item.id===selectedId);
    if(!q) {root.innerHTML=empty(corpusError?'Acervo indisponível':'Carregando acervo');return;}
    const a=q.variants.original,b=q.variants.candidate;
    const review=curation?.decisions?.find(item=>item.queryId===q.id),claudeReview=claudeDecision(q.id),qwenReview=qwenDecision(q.id);
    const claudeReviewMarkup=claudeReview?'<div class="detail-heading"><span>'+badge(claudeReview.decision==='candidate'?'Claude · candidata recomendada':claudeReview.decision==='original'?'Claude · original mantida':'Claude · revisão humana',claudeReview.decision==='candidate'?'positive':'')+'</span><span class="caption">confiança '+number(claudeReview.confidence*100,0)+'%</span></div><ul class="phase-list">'+claudeReview.rationale.map(item=>'<li>'+escape(item)+'</li>').join('')+'</ul><p class="evidence-note">Riscos Claude: '+escape(claudeReview.semanticRisks.join(' · '))+'</p>':'';
    const qwenReviewMarkup=qwenReview?'<div class="detail-heading"><span>'+badge(qwenReview.decision==='candidate'?'Qwen · candidata recomendada':qwenReview.decision==='original'?'Qwen · original mantida':'Qwen · revisão humana',qwenReview.decision==='candidate'?'positive':'')+'</span><span class="caption">confiança '+number(qwenReview.confidence*100,0)+'%</span></div><ul class="phase-list">'+qwenReview.rationale.map(item=>'<li>'+escape(item)+'</li>').join('')+'</ul><p class="evidence-note">Riscos Qwen: '+escape(qwenReview.semanticRisks.join(' · '))+'</p>':'';
    const reviewCard=review?card('Curadoria por IA <span class="subtle">· artefato não executável</span>',
      '<div class="detail-heading">'+badge(review.decision==='candidate'?'Codex · candidata recomendada':review.decision==='original'?'Codex · original mantida':'Codex · revisão humana',review.decision==='candidate'?'positive':'')+'<span class="caption">confiança '+number(review.confidence*100,0)+'%</span></div><ul class="phase-list">'+review.rationale.map(item=>'<li>'+escape(item)+'</li>').join('')+'</ul><p class="evidence-note">Riscos Codex: '+escape(review.semanticRisks.join(' · '))+'</p>'+claudeReviewMarkup+qwenReviewMarkup):'';
    root.innerHTML='<div class="dash-card"><div class="detail-heading"><span class="eyebrow">'+q.id+'</span>'+badge(q.control?'Controle negativo':change(q)>0?'Melhoria preliminar':'Regressão medida',q.control?'':change(q)>0?'positive':'negative')+'</div><h2>'+escape(q.title)+'</h2><p class="card-copy">'+escape(q.expected)+'</p><div class="kpi-grid three">'+kpi('Original',number(a.median_execution_ms)+' ms','Mediana · '+number(corpus.repetitions,0)+' repetições')+kpi('Candidata',number(b.median_execution_ms)+' ms','Alternativa escrita manualmente')+kpi('Resultado',number(q.result_rows,0)+' linhas','Equivalência validada · dois seeds','positive')+'</div><ol class="phase-track"><li class="complete">Dataset</li><li class="complete">Plano</li><li class="complete">Alternativa manual</li><li class="complete">Equivalência</li><li class="complete">Medição</li><li class="'+(review?'complete':'')+'">Curadoria IA</li><li>Parecer humano</li></ol><p class="evidence-note">A IA apenas selecionou entre duas variantes versionadas já validadas; nenhum SQL gerado pelo modelo foi executado.</p></div>'+reviewCard+
      card('Hipótese e limites','<p class="card-copy">'+escape(q.hypothesis)+'</p><p class="evidence-note">'+escape(q.risk)+'</p>')+
      '<div class="query-panels">'+['original','candidate'].map(v=>card(v==='original'?'SQL original':'SQL candidato · manual','<pre class="sql"><code>'+escape(q.variants[v].sql)+'</code></pre>')).join('')+'</div>'+
      card('Planos observados <span class="subtle">· primeira repetição do seed 42, escala 3</span>','<div class="plan-columns">'+['original','candidate'].map(v=>'<div><h3>'+(v==='original'?'Original':'Candidata')+'</h3><ul class="plan-tree">'+planTree(q.variants[v].plan[0].Plan)+'</ul><details><summary>JSON completo do plano</summary><pre class="sql">'+escape(JSON.stringify(q.variants[v].plan,null,2))+'</pre></details></div>').join('')+'</div><p class="evidence-note">Custo do planner não é preço nem tempo medido. Nós e linhas vêm de EXPLAIN ANALYZE no sandbox.</p>')+
      card('Amostras de execução · ms','<div class="table-wrap"><table><thead><tr><th>Repetição</th><th>Original</th><th>Candidata</th></tr></thead><tbody>'+a.runs.map((run,i)=>'<tr><td>'+(i+1)+'</td><td>'+number(run.execution_ms)+'</td><td>'+number(b.runs[i].execution_ms)+'</td></tr>').join('')+'</tbody></table></div>'+measuredNote);
  }
  function renderAudit() {
    const events=caseEvents?.events||[];
    section('auditoria').innerHTML=card('Eventos do caso local',caseEvents?'<p class="caption">'+escape(caseEvents.id)+' · '+(new URLSearchParams(location.search).has('demo')?'simulação não persistida':'auditoria SQLite')+'</p><ol class="audit-events">'+events.map(e=>'<li><span class="activity-dot"></span><div><strong>'+escape(e.type)+'</strong><p>'+escape(e.detail)+'</p><time>'+escape(new Date(e.at).toLocaleString('pt-BR'))+'</time></div></li>').join('')+'</ol>':empty('Nenhum caso carregado'))+
      card('Execuções verificadas do acervo','<p class="evidence-note">Evidências estáticas do experimento; não são eventos coletados em tempo real.</p><div class="table-wrap"><table><thead><tr><th>Query</th><th>Escopo</th><th>Verificação</th><th>Origem</th></tr></thead><tbody>'+cases().map(q=>'<tr><td><a class="text-link" href="#caso/'+q.id+'">'+q.id+'</a></td><td>Escala 3 · seeds 42 e 43</td><td>Equivalência aprovada</td><td>Laboratório sintético</td></tr>').join('')+'</tbody></table></div>')+
      (curation?card('Curadoria de IA auditável','<p class="evidence-note">Cada linha possui resposta estruturada, duração e evento bruto de usage. Nenhuma decisão substitui o parecer humano.</p><div class="table-wrap"><table><thead><tr><th>Query</th><th>Codex</th><th>Claude</th><th>Qwen</th><th>Estado</th></tr></thead><tbody>'+curation.decisions.map(item=>{const claude=claudeDecision(item.queryId),qwen=qwenDecision(item.queryId);return '<tr><td>'+item.queryId+'</td><td>'+escape(item.decision)+' · '+number(item.confidence*100,0)+'%</td><td>'+(claude?escape(claude.decision)+' · '+number(claude.confidence*100,0)+'%':'não executado')+'</td><td>'+(qwen?escape(qwen.decision)+' · '+number(qwen.confidence*100,0)+'%':'não executado')+'</td><td>Artefatos preservados</td></tr>'}).join('')+'</tbody></table></div>'):'');
  }
  function renderUsage() {
    const root=section('consumo');
    if(!curation){root.innerHTML=empty(curationError?'Evidência de IA indisponível':'Carregando evidência de IA');return;}
    const actual=curation.actualExecution,tokens=actual.tokens,prices=curation.pricing.projections,claude=claudeExecution(),qwen=qwenExecution(),externalRuns=additionalExecutions(),primaryExternal=qwen||claude;
    const avg=Math.round(tokens.input_tokens/actual.runs);
    root.innerHTML='<div class="notice">10 curadorias reais concluídas com '+escape(actual.model)+(externalRuns.length?' e '+externalRuns.map(item=>item.runs+' com '+escape(modelLabel(item))).join(', '):'')+' em sandbox somente leitura. A IA escolheu apenas entre variantes versionadas; nenhuma decisão substitui o parecer humano.</div><div class="kpi-grid four">'+
      kpi('Tokens de entrada',number(tokens.input_tokens,0),'Média '+number(avg,0)+' por query')+
      kpi('Tokens de saída',number(tokens.output_tokens,0),'Inclui '+number(tokens.reasoning_output_tokens,0)+' de reasoning reportado')+
      kpi('Entrada em cache',number(tokens.cached_input_tokens,0),'Subconjunto do total de entrada')+
      kpi(primaryExternal?modelLabel(primaryExternal)+' · custo real':'Projeção GPT',primaryExternal?'$ '+number(primaryExternal.billedCostUsd,6):'$ '+number(prices['gpt-6-sol'].usd,6),primaryExternal?primaryExternal.runs+' execuções · valor reportado':'USD · tarifa de '+escape(curation.pricing.effectiveDate))+'</div><div class="query-panels">'+
      card('Fases da execução por IA','<ol class="phase-list"><li>Contexto sanitizado<small>Somente SQL sintético, sem credenciais ou dados do target</small></li><li>Curadoria estruturada<small>Seleção entre original/candidata versionadas</small></li><li>Validação já medida<small>Equivalência multiconjunto em dois seeds e 280 planos</small></li><li>Decisão humana<small>Pendente e exibida como pendente; não automatizada</small></li></ol>')+
      card('Custos reportados e projeção contextual','<ul class="metric-list"><li>Codex / GPT-6 Sol · custo faturado <strong>não reportado pela assinatura</strong></li><li>Codex / GPT-6 Sol · equivalência API <strong>$ '+number(prices['gpt-6-sol'].usd,6)+'</strong></li>'+(externalRuns.length?externalRuns.map(item=>'<li>'+escape(modelLabel(item))+' · custo real reportado <strong>$ '+number(item.billedCostUsd,6)+'</strong></li><li>'+escape(modelLabel(item))+' · '+(item.provider==='openrouter'?'entrada / saída / total':'cache criado / lido / saída')+' <strong>'+(item.provider==='openrouter'?number(item.tokens.input_tokens,0)+' / '+number(item.tokens.output_tokens,0)+' / '+number(item.tokens.total_tokens,0):number(item.tokens.cache_creation_input_tokens,0)+' / '+number(item.tokens.cache_read_input_tokens,0)+' / '+number(item.tokens.output_tokens,0))+'</strong></li>').join(''):'<li>Benchmarks externos <strong>não executados</strong></li>')+'</ul><p class="evidence-note">Cada custo é reportado pelo respectivo provedor. Modelos usaram tokenização, cache e volume de saída diferentes; compare custo e qualidade do protocolo, não preço por token isolado.</p>')+'</div>'+
      card('Codex · usage e decisão por query','<div class="table-wrap"><table><thead><tr><th>Query</th><th>Decisão</th><th>Confiança</th><th>Input</th><th>Cache</th><th>Output</th><th>Duração</th></tr></thead><tbody>'+curation.decisions.map(item=>'<tr><td><a class="text-link" href="#caso/'+item.queryId+'">'+item.queryId+'</a></td><td>'+escape(item.decision)+'</td><td>'+number(item.confidence*100,0)+'%</td><td>'+number(item.usage.input_tokens,0)+'</td><td>'+number(item.usage.cached_input_tokens,0)+'</td><td>'+number(item.usage.output_tokens,0)+'</td><td>'+number(item.durationMs/1000,1)+' s</td></tr>').join('')+'</tbody></table></div>')+
      externalRuns.map(execution=>{const openrouter=execution.provider==='openrouter';return card(escape(modelLabel(execution))+' · usage e decisão por query','<div class="table-wrap"><table><thead><tr><th>Query</th><th>Decisão</th><th>Confiança</th><th>'+ (openrouter?'Input':'Cache criado') +'</th><th>'+ (openrouter?'Output':'Cache lido') +'</th><th>'+ (openrouter?'Duração':'Output') +'</th><th>Custo</th></tr></thead><tbody>'+execution.decisions.map(item=>'<tr><td><a class="text-link" href="#caso/'+item.queryId+'">'+item.queryId+'</a></td><td>'+escape(item.decision)+'</td><td>'+number(item.confidence*100,0)+'%</td><td>'+number(openrouter?item.usage.prompt_tokens:item.usage.cache_creation_input_tokens,0)+'</td><td>'+number(openrouter?item.usage.completion_tokens:item.usage.cache_read_input_tokens,0)+'</td><td>'+ (openrouter?number(item.durationMs/1000,1)+' s':number(item.usage.output_tokens,0)) +'</td><td>$ '+number(item.reportedCostUsd,6)+'</td></tr>').join('')+'</tbody></table></div><p class="evidence-note">Custo e tokens foram reportados pelo provedor. Q01 permanece uma decisão cautelosa: os seeds divergem e a promoção da candidata continua pendente de revisão humana.</p>');}).join('')+
      '<p class="evidence-note">Eventos brutos por provedor foram preservados; nenhuma contagem ausente foi convertida em zero.</p><a class="text-link" href="/curation.json" download="vigil-curation-summary.json">Baixar relatório JSON reproduzível →</a>';
  }
  function renderRoadmap() {
    const steps=[
      ['Concluído','Interface e navegação','Temas claro/escuro, cards responsivos e seis áreas com responsabilidades próprias.'],
      ['Concluído','Acervo de 10 queries complexas','Dois seeds, equivalência, casos-limite e 80 planos medidos.'],
      ['Concluído','Telemetria contínua','Deltas, reset, sessões, backoff, pausa e retenção SQLite integrados; storage do provedor permanece indisponível sem adapter.'],
      ['Concluído','Baseline e planos','Escala 3, dois seeds, sete repetições por variante e 280 planos completos.'],
      ['Concluído','IA local e tokens','10 curadorias reais, usage bruto por tentativa, preços datados e projeções separadas da fatura.'],
      ['Parcial','Validação e apresentação','SQL, planos, equivalência, decisões da IA e custos visíveis. Parecer humano e ensaio da defesa permanecem atividades humanas.']
    ];
    section('roteiro').innerHTML='<div class="roadmap-grid">'+steps.map(([status,title,description],i)=>'<article class="dash-card roadmap-card"><span class="step-number">'+String(i+1).padStart(2,'0')+'</span><div>'+badge(status,status==='Concluído'?'positive':'')+'<h2>'+title+'</h2><p class="card-copy">'+description+'</p></div></article>').join('')+'</div><div class="notice">VIG-087: implementação técnica concluída nesta branch de preparação. Revisão final, publicação do PR empilhado e parecer humano permanecem pendentes; não haverá merge automático.</div>';
  }
  const telemetrySamples = () => (telemetrySnapshot?.samples||[])
    .filter(sample=>sample.state!=='error')
    .slice().reverse();
  const timeLabels = samples => samples.map(sample=>new Date(sample.observedAt).toLocaleTimeString('pt-BR'));
  const plotDefinitions = () => {
    const samples=telemetrySamples();
    const available=samples.filter(sample=>sample.state==='available');
    const activity=samples.filter(sample=>sample.activeSessions!=null&&sample.waitingSessions!=null);
    const labels=['−30','−25','−20','−15','−10','−5','agora'];
    return {
      comparison:{labels:cases().map(q=>q.id),series:[{label:'Original',values:cases().map(q=>q.variants.original.median_execution_ms)},{label:'Candidata manual',values:cases().map(q=>q.variants.candidate.median_execution_ms)}],unit:'ms'},
      calls:preview?{labels,series:[{label:'Chamadas',values:[72,84,65,93,81,95,89]}],unit:'chamadas',preview:true}:
        {labels:timeLabels(available),series:[{label:'Chamadas',values:available.map(sample=>sample.calls)}],unit:'chamadas',real:true},
      latency:preview?{labels,series:[{label:'Média',values:[25,24,22,23,21,20,19]}],unit:'ms',preview:true}:
        {labels:timeLabels(available.filter(sample=>sample.meanExecutionMillis!=null)),series:[{label:'Média ponderada',values:available.filter(sample=>sample.meanExecutionMillis!=null).map(sample=>sample.meanExecutionMillis)}],unit:'ms',real:true},
      sessions:preview?{labels,series:[{label:'Ativas',values:[12,16,13,18,14,17,15]},{label:'Em espera',values:[2,4,2,3,5,3,4]}],unit:'sessões',preview:true}:
        {labels:timeLabels(activity),series:[{label:'Ativas',values:activity.map(sample=>sample.activeSessions)},{label:'Em espera',values:activity.map(sample=>sample.waitingSessions)}],unit:'sessões',real:true},
      storage:preview?{labels,series:[{label:'Espaço livre',values:[82,81,81,80,79,79,78.2]}],unit:'GiB',preview:true}:
        {labels:localSamples.map(s=>new Date(s.at).toLocaleTimeString('pt-BR')),series:[{label:'Volume local',values:localSamples.map(s=>s.value)}],unit:'GiB',local:true}
    };
  };
  function redrawCharts() {
    const definitions=plotDefinitions();
    document.querySelectorAll('.chart-slot').forEach(slot=>{
      if(!slot.clientWidth) return;
      const plot=definitions[slot.dataset.plot];
      const wasOpen=Boolean(slot.querySelector('details[open]'));
      const restoreFocus=slot.contains(document.activeElement);
      if(!plot || (plot.preview&&!preview)){slot.innerHTML=empty('Aguardando coleta');return;}
      if(plot.real&&!plot.labels.length){slot.innerHTML=empty('Sem amostras persistidas');return;}
      if(plot.local&&!localSamples.length){slot.innerHTML=empty('Aguardando leitura local');return;}
      const width=Math.max(240,slot.clientWidth), height=245, left=42,right=width-20,top=14,bottom=206;
      const max=Math.max(1,...plot.series.flatMap(s=>s.values))*1.12;
      const x=i=>left+i*(right-left)/Math.max(1,plot.labels.length-1);
      const y=v=>bottom-v/max*(bottom-top);
      let drawing='';
      for(let i=0;i<5;i++){
        const value=max*i/4, yy=y(value);
        drawing+='<line x1="'+left+'" x2="'+right+'" y1="'+yy+'" y2="'+yy+'" class="gridline"/><text x="'+(left-8)+'" y="'+(yy+4)+'" text-anchor="end">'+number(value,0)+'</text>';
      }
      plot.labels.forEach((label,i)=>{
        drawing+='<line x1="'+x(i)+'" x2="'+x(i)+'" y1="'+top+'" y2="'+bottom+'" class="gridline vertical"/>';
        if(width>500||i%2===0||i===plot.labels.length-1) drawing+='<text x="'+x(i)+'" y="230" text-anchor="middle">'+label+'</text>';
      });
      plot.series.forEach((series,index)=>{
        const points=series.values.map((v,i)=>x(i)+','+y(v)).join(' ');
        drawing+='<polyline points="'+points+'" class="plot-line line-'+index+'" '+(index?'stroke-dasharray="5 3"':'')+'/>';
        series.values.forEach((v,i)=>drawing+='<circle cx="'+x(i)+'" cy="'+y(v)+'" r="3" class="point point-'+index+'"><title>'+escape(series.label+' · '+plot.labels[i]+': '+number(v)+' '+plot.unit)+'</title></circle>');
      });
      slot.innerHTML='<svg viewBox="0 0 '+width+' '+height+'" role="img" aria-label="'+escape((plot.preview?'Série sintética ilustrativa. ':plot.local?'Leituras reais do volume local nesta sessão. ':plot.real?'Amostras persistidas do target. ':'Dados medidos do laboratório. ')+plot.series.map(s=>s.label).join(' e '))+'">'+drawing+'</svg><details class="chart-data"><summary>Ver dados do gráfico</summary><div class="table-wrap"><table><thead><tr><th>'+(plot.preview?'Minutos':plot.local||plot.real?'Horário':'Query')+'</th>'+plot.series.map(s=>'<th>'+s.label+' ('+plot.unit+')</th>').join('')+'</tr></thead><tbody>'+plot.labels.map((label,i)=>'<tr><td>'+label+'</td>'+plot.series.map(s=>'<td>'+number(s.values[i])+'</td>').join('')+'</tr>').join('')+'</tbody></table></div></details>';
      if(slot.dataset.plot!=='comparison')slot.insertAdjacentHTML('afterbegin','<div class="legend">'+plot.series.map(s=>'<span>'+s.label+'</span>').join('')+'</div>');
      slot.querySelector('details').open=wasOpen;
      if(restoreFocus)slot.querySelector('summary').focus({preventScroll:true});
    });
  }
  function navigate() {
    const [key,id]=location.hash.slice(1).split('/');
    currentScreen=Object.hasOwn(screens,key)?key:'overview';
    if(id&&cases().some(q=>q.id===id)) selectedId=id;
    $('#workspace-title').textContent=screens[currentScreen][1];
    $('#workspace-description').textContent=screens[currentScreen][2];
    $('.source-badge').textContent=currentScreen==='telemetria'?'ORIGEM INDICADA POR MÉTRICA':currentScreen==='consumo'?'USAGE REPORTADO · CUSTO PROJETADO':'LABORATÓRIO · DADOS SINTÉTICOS';
    document.querySelectorAll('[data-screen]').forEach(el=>el.hidden=el.dataset.screen!==currentScreen);
    document.querySelectorAll('.rail-link,.segment').forEach(a=>{
      const active=a.hash==='#'+currentScreen;
      a.classList.toggle('active',active);
      if(active)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');
    });
    if(currentScreen==='caso'){renderCaseList();renderCaseDetail();}
    redrawCharts();
    startTelemetry();
  }
  addEventListener('hashchange',navigate);
  addEventListener('vigil:case-updated',e=>{caseEvents=e.detail;renderAudit();});
  renderOverview();renderTelemetry();renderCases();renderAudit();renderUsage();renderRoadmap();navigate();
  let lastWidth=0;
  new ResizeObserver(entries=>{
    const width=entries[0].contentRect.width;
    if(width!==lastWidth){lastWidth=width;redrawCharts();}
  }).observe(workspace);
  fetch('/corpus.json').then(response=>{
    if(!response.ok)throw new Error('Corpus unavailable');
    return response.json();
  }).then(data=>{
    if(data.schemaVersion!==1 || data.source!=='synthetic-lab-measured' || !Array.isArray(data.cases))throw new Error('Invalid corpus');
    corpus=data;renderOverview();renderCaseList();renderCaseDetail();renderAudit();navigate();
  }).catch(()=>{corpusError=true;renderOverview();renderCaseDetail();});
  fetch('/curation.json').then(response=>{
    if(!response.ok)throw new Error('Curation unavailable');
    return response.json();
  }).then(data=>{
    if(data.schemaVersion!==1||data.actualExecution?.runs!==10||!Array.isArray(data.decisions))throw new Error('Invalid curation');
    curation=data;renderUsage();renderCaseDetail();renderAudit();renderRoadmap();navigate();
  }).catch(()=>{curationError=true;renderUsage();});
})();
