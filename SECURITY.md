# Política de segurança

## Status

O Vigil é um projeto experimental e não deve ser implantado em produção. Não há canal de suporte com SLA, nem garantia de correção em prazo determinado.

## Relatar uma vulnerabilidade

Não publique detalhes exploráveis, credenciais ou dados sensíveis em issues públicas. Para relatar uma vulnerabilidade, contate o mantenedor do repositório por meio do perfil GitHub e inclua:

- descrição do impacto;
- passos mínimos de reprodução;
- versão ou commit afetado;
- evidências sem dados reais ou segredos.

## Princípios de segurança atuais

- acesso PostgreSQL de menor privilégio e somente leitura;
- sem DDL/DML no banco monitorado;
- sanitização e bloqueio de contexto inseguro antes de inferência externa;
- SQLite local para estado e auditoria de demonstração;
- dados sintéticos nos laboratórios versionados.

Consulte também [a documentação de acesso ao target](docs/architecture/postgres-acesso-target.md).
