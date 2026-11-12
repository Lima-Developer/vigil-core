(() => {
  const $ = selector => document.querySelector(selector);
  const escape = value => String(value ?? '').replace(/[&<>"']/g, char =>
    ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[char]));
  const number = (value, digits = 2) => value == null ? '—' :
    new Intl.NumberFormat('pt-BR', {maximumFractionDigits:digits}).format(value);
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
  let corpus = null, corpusError = false, selectedId = 'Q01', currentScreen = 'overview';
  let preview = new URLSearchParams(location.search).has('demo');
  let caseEvents = null;
  let localObservation = null, telemetryFailure = false, telemetryPaused = false;
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
  const measuredNote = '<p class="evidence-note">PostgreSQL 16.4 · dataset sintético · cache aquecido · alternativas manuais. Resultados exploratórios, sem conclusão estatística ou ganho garantido.</p>';
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
      kpi('Planos medidos','80','Inclui controle e regressões')+'</div>'+
      card('Tempo por consulta <span class="subtle">· mediana em ms</span>','<div class="legend"><span>Original</span><span>Candidata manual</span></div>'+chartSlot('comparison','Tempos medidos de cada consulta')+measuredNote,'main-chart')+
      '<div class="bottom-cards">'+card('Resultados observados','<ul class="metric-list"><li>Melhorias preliminares <strong class="positive">'+improved+'</strong></li><li>Regressões <strong class="negative">'+regressed+'</strong></li><li>Controle sem alteração <strong>1</strong></li></ul><a class="text-link" href="#caso">Revisar cada resultado →</a>')+
      card('Ambiente do experimento','<ul class="metric-list"><li>Banco <strong>PostgreSQL 16.4</strong></li><li>CPU / memória <strong>2 / 1 GiB</strong></li><li>Distribuições <strong>Seeds 42 e 43</strong></li></ul>')+
      card('Próxima entrega','<p class="card-copy">Conectar a coleta contínua com origem, janela e qualidade de cada métrica.</p><a class="text-link" href="#roteiro">Acompanhar roteiro →</a>')+'</div></div>'+
      card('Execuções do acervo <span class="subtle">Histórico</span>','<p class="caption">Seed 42 · '+escape(new Date(corpus.observedAt).toLocaleDateString('pt-BR'))+'</p><ul class="activity-list">'+cases().map(q=>'<li><a href="#caso/'+q.id+'"><span class="activity-dot"></span><span><strong>'+q.id+'</strong><small>Equivalência validada</small></span><span class="activity-time">'+number(q.variants.candidate.median_execution_ms)+' ms<small>Candidata</small></span></a></li>').join('')+'</ul>','activity-card')+'</div>';
  }
  function renderTelemetry() {
    section('telemetria').innerHTML='<div class="view-toolbar"><span class="subtle">Métricas operacionais do target</span><button id="preview-toggle" class="secondary-button" aria-pressed="'+preview+'">'+(preview?'Sair da prévia visual':'Ativar prévia visual')+'</button></div><div class="notice '+(preview?'demo':'')+'">'+(preview?'Prévia visual: séries sintéticas de layout. Não há coleta contínua conectada.':'Coleta contínua ainda não integrada. As métricas do banco permanecem indisponíveis.')+'</div><div class="kpi-grid four">'+
      kpi('Chamadas SQL',preview?'67.589':'—','Na janela ilustrativa de 30 min')+
      kpi('Latência média',preview?'22 ms':'—','Ponderada por chamadas')+
      kpi('Sessões em espera',preview?'4':'—','Espera não implica fila de pool')+
      kpi('Armazenamento local','<span id="local-space-value">'+(preview?'78,2 GiB':'—')+'</span>','<span id="local-space-origin">Volume do Vigil, não do target</span>')+'</div><div class="telemetry-controls" '+(preview?'hidden':'')+'><button id="pause-telemetry" class="secondary-button" aria-pressed="'+telemetryPaused+'">'+(telemetryPaused?'Retomar atualização':'Pausar atualização')+'</button><span id="telemetry-read-status" role="status">Leitura local a cada 30 s enquanto esta aba estiver visível.</span></div><div class="telemetry-grid">'+
      card('Atividade do banco <span class="subtle">· chamadas por intervalo</span>',chartSlot('calls','Atividade ilustrativa'))+
      card('Latência média <span class="subtle">· ms</span>',chartSlot('latency','Latência ilustrativa'))+
      card('Sessões <span class="subtle">· ativas e em espera</span>',chartSlot('sessions','Sessões ilustrativas'))+
      card('Espaço local disponível <span class="subtle">· GiB</span>',chartSlot('storage',preview?'Espaço ilustrativo':'Leituras reais do espaço local'))+'</div><p class="evidence-note">A primeira amostra, resets e falhas terão estados próprios. freeLocalStorage de um provedor de banco exige uma fonte específica; não é o espaço livre deste computador.</p>';
    $('#preview-toggle').onclick=()=>{preview=!preview;renderTelemetry();redrawCharts();startTelemetry();};
    $('#pause-telemetry').onclick=()=>{
      telemetryPaused=!telemetryPaused;
      $('#pause-telemetry').setAttribute('aria-pressed',String(telemetryPaused));
      $('#pause-telemetry').textContent=telemetryPaused?'Retomar atualização':'Pausar atualização';
      updateTelemetryRead();startTelemetry();
    };
    updateTelemetryRead();
  }
  function updateTelemetryRead() {
    if(preview) return;
    const available=localObservation?.state==='available';
    $('#local-space-value').textContent=available?number(localObservation.value/1073741824)+' GiB':'—';
    $('#local-space-origin').textContent=available?'Medição real · volume local do Vigil':'Volume local · leitura indisponível';
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
        if(data.schemaVersion!==1||!metric||metric.source!=='filesystem')throw new Error('Invalid telemetry');
        localObservation=metric;telemetryFailure=metric.state!=='available';
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
    root.innerHTML='<div class="dash-card"><div class="detail-heading"><span class="eyebrow">'+q.id+'</span>'+badge(q.control?'Controle negativo':change(q)>0?'Melhoria preliminar':'Regressão medida',q.control?'':change(q)>0?'positive':'negative')+'</div><h2>'+escape(q.title)+'</h2><p class="card-copy">'+escape(q.expected)+'</p><div class="kpi-grid three">'+kpi('Original',number(a.median_execution_ms)+' ms','Mediana · 3 repetições')+kpi('Candidata',number(b.median_execution_ms)+' ms','Alternativa escrita manualmente')+kpi('Resultado',number(q.result_rows,0)+' linhas','Equivalência validada · dois seeds','positive')+'</div><ol class="phase-track"><li class="complete">Dataset</li><li class="complete">Plano</li><li class="complete">Alternativa manual</li><li class="complete">Equivalência</li><li class="complete">Medição</li><li>Parecer humano</li></ol><p class="evidence-note">Fluxo do laboratório. Proposta por IA e aprovação humana ainda não executadas.</p></div>'+
      card('Hipótese e limites','<p class="card-copy">'+escape(q.hypothesis)+'</p><p class="evidence-note">'+escape(q.risk)+'</p>')+
      '<div class="query-panels">'+['original','candidate'].map(v=>card(v==='original'?'SQL original':'SQL candidato · manual','<pre class="sql"><code>'+escape(q.variants[v].sql)+'</code></pre>')).join('')+'</div>'+
      card('Planos observados <span class="subtle">· primeira repetição do seed 42</span>','<div class="plan-columns">'+['original','candidate'].map(v=>'<div><h3>'+(v==='original'?'Original':'Candidata')+'</h3><ul class="plan-tree">'+planTree(q.variants[v].plan[0].Plan)+'</ul><details><summary>JSON completo do plano</summary><pre class="sql">'+escape(JSON.stringify(q.variants[v].plan,null,2))+'</pre></details></div>').join('')+'</div><p class="evidence-note">Custo do planner não é preço nem tempo medido. Nós e linhas vêm de EXPLAIN ANALYZE no sandbox.</p>')+
      card('Amostras de execução · ms','<div class="table-wrap"><table><thead><tr><th>Repetição</th><th>Original</th><th>Candidata</th></tr></thead><tbody>'+a.runs.map((run,i)=>'<tr><td>'+(i+1)+'</td><td>'+number(run.execution_ms)+'</td><td>'+number(b.runs[i].execution_ms)+'</td></tr>').join('')+'</tbody></table></div>'+measuredNote);
  }
  function renderAudit() {
    const events=caseEvents?.events||[];
    section('auditoria').innerHTML=card('Eventos do caso local',caseEvents?'<p class="caption">'+escape(caseEvents.id)+' · '+(new URLSearchParams(location.search).has('demo')?'simulação não persistida':'auditoria SQLite')+'</p><ol class="audit-events">'+events.map(e=>'<li><span class="activity-dot"></span><div><strong>'+escape(e.type)+'</strong><p>'+escape(e.detail)+'</p><time>'+escape(new Date(e.at).toLocaleString('pt-BR'))+'</time></div></li>').join('')+'</ol>':empty('Nenhum caso carregado'))+
      card('Execuções verificadas do acervo','<p class="evidence-note">Evidências estáticas do experimento; não são eventos coletados em tempo real.</p><div class="table-wrap"><table><thead><tr><th>Query</th><th>Escopo</th><th>Verificação</th><th>Origem</th></tr></thead><tbody>'+cases().map(q=>'<tr><td><a class="text-link" href="#caso/'+q.id+'">'+q.id+'</a></td><td>Seeds 42 e 43</td><td>Equivalência aprovada</td><td>Laboratório sintético</td></tr>').join('')+'</tbody></table></div>');
  }
  function renderUsage() {
    section('consumo').innerHTML='<div class="notice">Nenhuma execução de LLM registrada. As alternativas do acervo foram escritas manualmente.</div><div class="kpi-grid four">'+['Tokens de entrada','Tokens de saída','Entrada em cache','Custo calculado'].map(label=>kpi(label,'—','Aguardando usage reportado pelo provedor')).join('')+'</div><div class="query-panels">'+
      card('Fases da execução por IA','<ol class="phase-list"><li>Contexto sanitizado<small>Schema, plano e hipótese permitidos</small></li><li>Proposta do modelo<small>Input, output e cache por tentativa</small></li><li>Validação no sandbox<small>Política SQL e equivalência</small></li><li>Comparação e decisão<small>Tempos, consumo e parecer humano</small></li></ol>')+
      card('Comparação de custos',empty('Sem consumo medido')+'<p class="evidence-note">Contagens ausentes não viram zero. Preços deverão ter data, moeda e modelo; projeções tarifadas serão separadas do valor da assinatura.</p>')+'</div>';
  }
  function renderRoadmap() {
    const steps=[
      ['Concluído','Interface e navegação','Temas claro/escuro, cards responsivos e seis áreas com responsabilidades próprias.'],
      ['Concluído','Acervo de 10 queries complexas','Dois seeds, equivalência, casos-limite e 80 planos medidos.'],
      ['Em andamento','Telemetria contínua','Contrato e leitura do volume local prontos. Faltam coletor PostgreSQL, deltas, reset e retenção.'],
      ['Parcial','Baseline e planos','Laboratório medido. Ampliar escala, repetições e conexão com o fluxo do sistema.'],
      ['Pendente','IA local e tokens','Integração explícita com provedor, contexto sanitizado e usage exato por tentativa.'],
      ['Parcial','Validação e apresentação','SQL, planos e resultados visíveis. Faltam propostas de IA, decisão e relatório completo.']
    ];
    section('roteiro').innerHTML='<div class="roadmap-grid">'+steps.map(([status,title,description],i)=>'<article class="dash-card roadmap-card"><span class="step-number">'+String(i+1).padStart(2,'0')+'</span><div>'+badge(status,status==='Concluído'?'positive':'')+'<h2>'+title+'</h2><p class="card-copy">'+description+'</p></div></article>').join('')+'</div><div class="notice">Integração VIG-087: revisão/QA e publicação ainda pendentes. O checkout de integração contém um conflito anterior de cherry-pick; este workspace usa a branch de preparação.</div>';
  }
  const plotDefinitions = () => ({
    comparison:{labels:cases().map(q=>q.id),series:[{label:'Original',values:cases().map(q=>q.variants.original.median_execution_ms)},{label:'Candidata manual',values:cases().map(q=>q.variants.candidate.median_execution_ms)}],unit:'ms'},
    calls:{labels:['−30','−25','−20','−15','−10','−5','agora'],series:[{label:'Chamadas',values:[72,84,65,93,81,95,89]}],unit:'chamadas',preview:true},
    latency:{labels:['−30','−25','−20','−15','−10','−5','agora'],series:[{label:'Média',values:[25,24,22,23,21,20,19]}],unit:'ms',preview:true},
    sessions:{labels:['−30','−25','−20','−15','−10','−5','agora'],series:[{label:'Ativas',values:[12,16,13,18,14,17,15]},{label:'Em espera',values:[2,4,2,3,5,3,4]}],unit:'sessões',preview:true},
    storage:preview?{labels:['−30','−25','−20','−15','−10','−5','agora'],series:[{label:'Espaço livre',values:[82,81,81,80,79,79,78.2]}],unit:'GiB',preview:true}:
      {labels:localSamples.map(s=>new Date(s.at).toLocaleTimeString('pt-BR')),series:[{label:'Volume local',values:localSamples.map(s=>s.value)}],unit:'GiB',local:true}
  });
  function redrawCharts() {
    const definitions=plotDefinitions();
    document.querySelectorAll('.chart-slot').forEach(slot=>{
      if(!slot.clientWidth) return;
      const plot=definitions[slot.dataset.plot];
      const wasOpen=Boolean(slot.querySelector('details[open]'));
      const restoreFocus=slot.contains(document.activeElement);
      if(!plot || (plot.preview&&!preview)){slot.innerHTML=empty('Aguardando coleta');return;}
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
      slot.innerHTML='<svg viewBox="0 0 '+width+' '+height+'" role="img" aria-label="'+escape((plot.preview?'Série sintética ilustrativa. ':plot.local?'Leituras reais do volume local nesta sessão. ':'Dados medidos do laboratório. ')+plot.series.map(s=>s.label).join(' e '))+'">'+drawing+'</svg><details class="chart-data"><summary>Ver dados do gráfico</summary><div class="table-wrap"><table><thead><tr><th>'+(plot.preview?'Minutos':plot.local?'Horário':'Query')+'</th>'+plot.series.map(s=>'<th>'+s.label+' ('+plot.unit+')</th>').join('')+'</tr></thead><tbody>'+plot.labels.map((label,i)=>'<tr><td>'+label+'</td>'+plot.series.map(s=>'<td>'+number(s.values[i])+'</td>').join('')+'</tr>').join('')+'</tbody></table></div></details>';
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
    $('.source-badge').textContent=currentScreen==='telemetria'?'ORIGEM INDICADA POR MÉTRICA':currentScreen==='consumo'?'SEM EXECUÇÕES DE LLM':'LABORATÓRIO · DADOS SINTÉTICOS';
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
})();
