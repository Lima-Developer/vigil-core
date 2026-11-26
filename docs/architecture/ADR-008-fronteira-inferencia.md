# ADR-008 — Fronteira segura e validada de inferência

- Status: aceita
- Data: 2026-11-26

## Contexto

A porta de inferência aceitava uma `String` livre. Isso permitia que adaptadores recebessem contexto sem prova de sanitização, construíssem prompts não versionados e retornassem afirmações sem grounding verificável.

## Decisão

1. `InferencePort` aceita somente `InferenceRequest`, composta por `SanitizedInferenceContext`, schema esperado, `PromptTemplateVersion` e timeout.
2. O contexto permitido contém apenas referências pseudonimizadas, métricas aprovadas e IDs de evidence. SQL bruto, literais e credenciais não fazem parte do contrato.
3. O adaptador constrói o prompt a partir de template versionado; chamadores não fornecem prompt livre.
4. `InferenceOutputValidator` valida schema, códigos permitidos, IDs de evidence e referências de catálogo/relação. Saída inválida é rejeitada antes de alcançar o domínio.
5. Ollama permanece o adaptador local configurável. Bedrock é um adaptador opcional, desacoplado por `BedrockRuntimeClient` e desligado por padrão; testes usam cliente falso e não fazem chamada AWS real.
6. A regra arquitetural impede assinaturas de `infer` em adaptadores que contornem `InferenceRequest`.

## Consequências

- Falhas de sanitização e grounding são fail-closed.
- A versão do template é auditável e reproduzível.
- Provedores podem variar sem alterar o domínio ou o contrato de segurança.
- Egress real continua dependente de configuração e governança externas ao recorte.
