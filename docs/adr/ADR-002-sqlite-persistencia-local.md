# ADR-002 — SQLite como persistência padrão da instalação local do Vigil

- **Status:** Aceito
- **Data:** 2026-08-19
- **Decisores:** Equipe Vigil
- **Contexto:** Vigil Core / Persistência e implantação

## Contexto

O Vigil precisa persistir informações necessárias para observar, diagnosticar e apoiar a curadoria de performance de consultas SQL:

- configuração do ambiente monitorado;
- telemetria compacta;
- baselines estatísticos;
- casos de análise;
- evidências, diagnósticos e hipóteses;
- resultados de validação;
- decisões humanas e trilha de auditoria.

O produto será inicialmente uma aplicação leve, executada dentro da infraestrutura do cliente, para monitorar um único target PostgreSQL ou Aurora PostgreSQL.

Exigir um PostgreSQL dedicado para cada instalação adicionaria custo operacional, credenciais, manutenção, monitoramento e infraestrutura adicional. Isso não é proporcional à necessidade da primeira versão do Vigil.

## Decisão

O Vigil utilizará **SQLite como mecanismo de persistência padrão**.

A base SQLite deverá ser mantida em volume persistente, fora da camada efêmera do contêiner, e utilizar o modo **Write-Ahead Logging (WAL)**.

A aplicação acessará a persistência por meio de portas do domínio. SQLite será uma implementação de infraestrutura substituível, permitindo adotar PostgreSQL ou outra tecnologia no futuro sem alterar regras de negócio, casos de uso ou modelos de domínio.

## Escopo de implantação

Nesta versão, uma instalação do Vigil monitora exclusivamente um target PostgreSQL ou Aurora PostgreSQL.

O target representa o banco ou cluster definido na configuração da instalação. Não há suporte a múltiplos targets, centralização de telemetria ou fila de processamento entre bancos.

Essa restrição é intencional. Ela reduz a superfície de acesso, simplifica o modelo de segurança, preserva baixo custo operacional e mantém o escopo do TCC controlado.

Todos os dados persistidos pertencem implicitamente ao único target configurado na instalação.

## Escopo de dados persistidos

O SQLite armazenará:

- perfil e capacidades do target monitorado;
- configuração de coleta e políticas de alerta;
- snapshots compactos de telemetria;
- baselines `ABSENT`, `PROVISIONAL` e `MATURE`;
- famílias de queries e identificadores internos;
- `AnalysisCase` e eventos de transição de estado;
- diagnósticos, hipóteses e resultados de validação;
- decisões de curadoria e registros de auditoria;
- referências a artefatos protegidos, quando necessário.

O SQLite não deverá armazenar resultados de consultas.

O armazenamento de SQL normalizado, sanitizado ou bruto será condicionado à política de privacidade do Vigil. SQL bruto e valores de parâmetros não devem ser persistidos por padrão.

## Operação com SQLite

A instalação deverá seguir estas regras:

- executar apenas uma instância escritora do Vigil para cada arquivo SQLite;
- usar volume local persistente;
- ativar WAL para permitir leitura da interface durante escritas do coletor;
- manter retenção configurável para snapshots detalhados;
- agregar ou expirar telemetria histórica conforme política;
- executar backups consistentes periodicamente;
- incluir no processo de backup o estado associado ao WAL;
- proteger o volume por permissões do sistema operacional e controles da infraestrutura.

O arquivo SQLite não deve ser compartilhado entre hosts por NFS, EFS ou outro filesystem de rede.

## Consequências positivas

- Elimina a necessidade de servidor de banco dedicado.
- Reduz custo e complexidade de instalação.
- Simplifica distribuição como imagem Docker.
- Facilita execução local, laboratório e demonstração do TCC.
- Mantém telemetria e casos de análise dentro da infraestrutura do cliente.
- Reduz superfície de ataque de rede, pois não exige porta de banco adicional exposta.
- Permite backup, migração e restauração simplificados.
- É adequado ao padrão de escrita do Vigil: coleta periódica e processamento controlado por uma única aplicação.

## Consequências negativas

- SQLite permite apenas um escritor por vez.
- Não há suporte nativo para múltiplas réplicas ativas gravando na mesma base.
- Alta disponibilidade e failover não são fornecidos automaticamente.
- Crescimento de retenção sem agregação pode aumentar o arquivo de dados.
- Controle de acesso ao conteúdo depende da aplicação, das permissões do volume e da infraestrutura; SQLite não fornece autenticação de usuários de banco.
- O produto deve possuir estratégia explícita de backup e recuperação.

## Alternativas consideradas

### PostgreSQL dedicado por instalação

Rejeitada como padrão inicial.

É uma alternativa adequada para ambientes com múltiplas réplicas, alta disponibilidade, retenção extensa ou alto grau de concorrência. Porém, adiciona operação e custo desnecessários para o padrão de implantação local, leve e de target único.

Poderá ser oferecida posteriormente como adapter alternativo de persistência.

### Banco centralizado compartilhado por vários Vigils

Rejeitada para esta versão.

Aumentaria o escopo para centralização de telemetria, autenticação entre agentes, isolamento entre bancos, rede, replicação e governança multi-target. Esses problemas não são necessários para validar a proposta principal do Vigil.

### Armazenamento efêmero no contêiner

Rejeitado.

Baselines, telemetria, casos e decisões de curadoria precisam sobreviver a reinicializações e atualizações da aplicação.

## Evolução futura

O suporte multi-target poderá ser introduzido futuramente por meio de uma evolução do modelo de configuração, persistência e controle de acesso.

Caso o produto passe a exigir múltiplos coletores, alta disponibilidade, centralização de muitos bancos, grande retenção histórica ou dashboards corporativos compartilhados, será criado um adapter compatível com PostgreSQL ou tecnologia apropriada.

Essa evolução não deverá alterar os princípios de observabilidade progressiva, diagnóstico baseado em evidências, privacidade e curadoria humana definidos no core.

## Referências

- [SQLite — Appropriate Uses For SQLite](https://www.sqlite.org/whentouse.html)
- [SQLite — Write-Ahead Logging](https://www.sqlite.org/wal.html)
- [PostgreSQL — pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)