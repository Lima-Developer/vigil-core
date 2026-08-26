# Vertical Slice 1 - Consulta seletiva sem índice

## 1. Propósito

Implementar o primeiro fluxo vertical do Vigil de ponta a ponta, usando um único cenário sintético e controlado.

O objetivo não é concluir todos os recursos do MVP. Este slice valida que o núcleo produto consegue transformar uma consulta
observada em um caso auditável, produzir uma hipótese fundamentada, validá-la em ambiente isolado e encaminhá-la para curadoria humana.

## 2. Decisões arquiteturais relacionadas

Este slice implementa parcialmente as decisões abaixo:

- [ADR-001 - Observabilidade progressiva e baseline adaptativo](../adr/ADR-001-observabilidade-progressiva-e-baseline-adaptativo.md)
  - O caso pode ser identificado por impacto absoluto, sem baseline histórico prévio.

- [ADR-002 - SQLite como persistência padrão da instalação local do Vigil](../adr/ADR-002-sqlite-persistencia-local.md)
  - A persistência definitiva será local e atenderá somente um target.
  - Neste primerio slice, adapters em memória são permitidos para validar o fluxo de domínio antes da intergração com SQLite.

## 3. Cenário de negócio

Uma aplicação executa repetidamente uma consulta seletiva contra uma tabela grande de usuários:

```SQL
SELECT id, nome
FROM usuarios
WHERE cpf = '099.888.765-08';
```

A tabela possui muitos registros, a coluna `cpf` não possui índice e o plano de execução indica um `Seq Scan`.

O Vigil deve identificar que há índices de um full scan potencialmente evitável, gerar uma hipótese de índice, validá-la
em ambiente isolado e encaminhar o resultado para decisão humana.

## 4. Resultado esperado

Ao final do fluxo, deve existir um `AnalysisCase` auditável contendo:
- consulta identificada por fingerprint;
- métricas e evidências coletadas;
- plano de execução analisado;
- contexto sanitizado;
- diagnóstico;
- hipótese de otimização;
- resultado da validação;
- estado aguardando curadoria humana;
- histórico de eventos e transições;

O Vigil não pode executar alterações no banco monitorado.

## 5. Fluxo ponta a ponta
```txt
ObservedQuery
    → seleção por imapcto absoluto
    → Evidence
    → AnalysisCase
    → SanitizedContext
    → Diagnosis
    → OptimizationHypothesis
    → ValidationResult
    → HumanDecision
```

Fluxo de estados:

```txt
DISCOVERED
→ TRIAGED
→ EVIDENCE_COLLECTED
→ SANITIZED
→ DIAGNOSED
→ HYPOTHESIS_PROPOSED
→ VALIDATING
→ VALIDATED
→ AWAITING_HUMAN
→ APPROVED | REJECTED
```

## 6. Escopo deste Slice

**Incluído**
- Um único cenário sintético: filtro seletivo em coluna sem índice.
- Uma regra determinística: `POSSIBLE_AVOIDABLE_SEQ_SCAN`.
- Criação e transição auditável de `AnalysisCase`.
- Sanitização de contexto.
- Contrato `InferecePort`.
- Adapter falso de inferência, com resposta estruturada e fundamentada.
- Contrato `ValidationPort`.
- Adapter falso de validação, simulando ganho de desempenho.
- Repositório em memória.
- Teste de aceitação ponta a ponta.

**Explicitamente adiado**
- JDBC e conexão real com PostgreSQL
- Coleta real via `pg_stat_statements`.
- `EXPLAIN (FORMAT JSON)` real.
- SQLite
- Testcontainers
- HypoPG
- Ollama, vLLM e Amazon Bedrock
- API REST, interface web e Docker
- Baseline histórico
- Outros tipos de diagnóstico

## 7. Objetos de domínio mínimos
| Objeto                    | Responsabilidade neste slice                                        |
|---------------------------|---------------------------------------------------------------------|
| `ObservedQuery`           | Representar uma consulta observada sem guardar SQL bruto            |
| `QuerySnapshot`           | Representar métrivcas de uma janela observada                       |
| `Evidence`                | Registrar um fato verificável, como Seq Scan ou alto impacto        |
| `AnalysisCase`            | Agregar o ciclo de vida, eventos e artefatos da análise             |
| `SanitizedContext`        | Transportar somente dados seguros para inferência                   |
| `Diagnosis`               | Registrar a interpretação do problema com referências às evidências |
| `OptimizationHypothesis`  | Representar uma sugestão que poderá ser validada                    |
| `ValidationResult`        | Registrar o resultado técnico da hipótese no sandbox                |
| `HumanDecision`           | Registrar a decisão humana final e sua justificativa                |

## 8. Ports necessários
| Port                      | Responsabilidade                                               |
|---------------------------|----------------------------------------------------------------|
| `QueryTelemetryPort`      | Fornecer métricas de uma consulta observada                    |
| `QueryPlanPort`           | Fornecer o plano de execução e metadados necessários           |
| `InferencePort`           | Receber contexto sanitizado e devolver diagnóstico estruturado |
| `ValidationPort`          | Validar uma hipótese fora do target monitorado                 |
| `AnalysisCaseRepository`  | Salvar e recuperar casos de análise                            |

Neste slice, todos os ports terão adapters falsos ou em memória.

## 9. Regras de negócio
- SQL bruto não deve fazer parte de `ObservedQuery`, `AnalysisCase`, logs ou contexto de inferência
- Um `Seq Scan` isolado não é diagnóstico suficiente.
- A regra `POSSIBLE_AVOIDABLE_SEQ_SCAN` deve exigir, no mínimo:
  - relação de porte relevante;
  - filtro seletivo;
  - ausência de índice compatível;
  - presença de `Seq Scan` no plano;
  - sinal de impacto relevante;
- Diagnóstico e hipótese devem citar IDs de evidências existentes.
- Uma resposta de inferência sem referências válidas deve ser rejeitada
- O fluxo pode concluir que índice não é recomendado
- A validação somente pode ocorrer por `ValidationPort`
- Nenhuma decisão humana pode acionar DDL ou DML no target monitorado
- Toda transição de estado deve gerar um evento com origem, motivo e data

## 10. Critérios de Aceite
- [ ] Um teste cria uma `ObservedQuery` sem SQL bruto.
- [ ] O fluxo identifica evidência de `POSSIBLE_AVOIDABLE_SEQ_SCAN`.
- [ ] Um `AnalysisCase` é criado e percorre os estados previstos.
- [ ] cada transição cria ume vento auditável
- [ ] O conteto entregue a `InferencePort` é sanitizado
- [ ] A inferência falsa devolve diagnóstico estruturado com referências válidas
- [ ] Uma resposta sem grounding é rejeitada
- [ ] A hipótese de índice é criada somente quando compatível com as evidências
- [ ] A validação falsa retorna `VALIDATED`, `INVALIDATED` ou `INCONCLUSIVE`.
- [ ] O caso chega a `AWAITING_HUMAN`.
- [ ] Uma decisão humana é registrada
- [ ] O teste prova que nenhuma operação é enviada ao banco monitorado

## 11. Demonstração esperada

A demonstração deve imprimir ou expor um resumo equivalente a:

```markdown
Caso: CASE-001
Fingerprint: <identificador seguro>
Status: AWAITING_HUMAN

Evidências:
- Seq Scan em usuarios
- Filtro seletivo em cpf
- Ausência de índice compatível
- Impacto acima do limiar configurado

Diagnóstico:
- POSSIBLE_AVOIDABLE_SEQ_SCAN

Hipótese:
- Criar índice em usuarios(cpf)

Validação isolada:
- VALIDATED
- Melhoria estimada: X%

Decisão humana:
- Pendente
```

## 12. Definição de Pronto

O Vertical Slice 1 está concluído quando:
1. O teste ponta a ponta passa de forma reproduzível;
2. Todas as regras de segurança deste documento são verificadas por testes;
3. Não existe integração real necessária para executar o fluxo;
4. O fluxo demonstra o valor arquitetural do Vigil;
5. A próxima evolução pode substituir um adapter falso por uma adapter real sem alterar o domínio

> ⚠️ **Importante:**
A parte mais importante é a seção **Explicitamente adiado**. Ela impede que o primeiro slice vire, acidentalmente, o MVP inteiro.



