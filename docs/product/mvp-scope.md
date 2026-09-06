- Interface web simples.
- Docker Compose com PostgreSQL sintético, Vigil e volume SQLite.
- Empacotamento Docker da aplicação.
- Suporte a novos tipos PostgreSQL, como `jsonb`, arrays e enums.

# FORA DE ESCOPO DO MVP

- Suportar SQL PostgreSQL arbitrário no Sandbox automático.
- Clonar fielmente produção, incluindo dados reais, volume idêntico ou valores
  de negócio.
- Alterar custos do planner para simular artificialmente milhões de linhas.
- Monitorar múltiplos targets simultaneamente.
- Executar `CREATE INDEX`, `ALTER TABLE`, `ANALYZE` ou qualquer DDL/DML no
  target monitorado.
- Executar `EXPLAIN ANALYZE` no target.
- Usar dados reais da empresa no laboratório, dataset ou prompts.
- Persistir SQL bruto como comportamento padrão.
- Dashboard corporativo multiusuário.
- Alta disponibilidade, replicação ou persistência centralizada.
- Baseline sazonal `MATURE`.
- Diagnosticar todos os tipos de problema PostgreSQL.
- Suporte inicial a Redshift, MySQL, Oracle ou SQL Server.
- Garantir economia financeira real em cloud.
- HMAC, KMS e integração completa com secret managers.
- Agentes autônomos, tool calling e arquiteturas multiagente.
- Permitir que IA aplique mudanças ou tome decisão de curadoria.

# Cenários mínimos de avaliação

1. **Sequential Scan correto em tabela pequena**  
   Não deve produzir finding de scan evitável.

2. **Filtro seletivo em tabela grande sem índice**  
   Deve tornar-se candidata, produzir finding, gerar hipótese e permitir
   validação de índice no Sandbox.

3. **Filtro seletivo com índice existente adequado**  
   Não deve sugerir índice redundante.

4. **Alto RPM com baixo impacto total**  
   Não deve abrir caso apenas pelo número de chamadas.

5. **Alto impacto absoluto sem baseline**  
   Deve abrir caso com `BaselineConfidence.ABSENT`.

6. **Join simples custoso**  
   Deve ser classificado como shape suportado ou gerar diagnóstico limitado,
   conforme a capacidade implementada.

7. **Shape não suportado**  
   Deve ser observado e diagnosticado quando possível, mas validado como
   `INCONCLUSIVE` com motivo explícito.

8. **Contexto inseguro ou não sanitizável**  
   Deve bloquear inferência externa e registrar evento auditável.

9. **Resposta de modelo sem grounding**  
   Deve ser rejeitada e não alterar estado do caso.

10. **Hipótese sem benefício no Sandbox**  
    Deve terminar como `INVALIDATED`.

# Critérios de sucesso

As metas são critérios do experimento; não são resultados presumidos.

- Precisão e recall da seleção de candidatas.
- Precisão dos findings determinísticos.
- Taxa de recomendações inseguras: alvo igual a zero.
- Taxa de vazamento no corpus sanitizado: alvo igual a zero.
- Tempo entre coleta suficiente e abertura de caso.
- Overhead de coleta no ambiente de laboratório.
- Taxa de classificação correta entre `VALIDATED`, `INVALIDATED` e
  `INCONCLUSIVE`.
- Percentual de saídas de IA rejeitadas corretamente por ausência de grounding.
- Comparação de qualidade, latência e custo entre regras e fluxo híbrido.
- Toda validação deve registrar seu `FidelityLevel`.

# Critério de aceite

O MVP estará aceito quando demonstrar, em cenário sintético reproduzível:

```text
SELECT impactante
→ Candidate
→ AnalysisCase auditável
→ Evidence
→ SanitizedContext
→ Diagnosis fundamentado
→ hipótese de índice
→ Sandbox suportado
→ ValidationResult
→ curadoria humana
```

A demonstração deve provar que:

- o target nunca recebe DDL ou DML do Vigil;
- dados reais não entram no dataset ou no provider de inferência;
- consultas fora dos shapes suportados não recebem validação enganosa;
- resultados de Sandbox possuem fidelidade declarada;
- IA não possui autoridade para alterar o banco ou aprovar decisões;
- todo resultado é rastreável até suas evidências e eventos.

# Decisões arquiteturais vinculantes

Este escopo implementa as decisões registradas nos ADRs abaixo. Alterações que
contradigam essas decisões exigem revisão ou novo ADR.

- [ADR-001 — Observabilidade progressiva e baseline adaptativo](../adr/ADR-001-observabilidade-progressiva-e-baseline-adaptativo.md)  
  O Vigil funciona desde o primeiro snapshot por impacto absoluto. Baseline é
  progressivo e não bloqueia o funcionamento inicial.

- [ADR-002 — SQLite como persistência padrão da instalação local do Vigil](../adr/ADR-002-sqlite-persistencia-local.md)  
  O MVP atende um único target e usa SQLite local para estado operacional.

- [ADR-003 — Monólito modular e arquitetura hexagonal](../adr/ADR-003-monolito-modular-arquitetura-hexagonal.md)  
  O domínio e a aplicação permanecem independentes de PostgreSQL, SQLite,
  Testcontainers, providers de IA e Spring Boot.