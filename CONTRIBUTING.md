# Como contribuir

Obrigado por considerar uma contribuição ao Vigil. O projeto está em fase experimental e prioriza segurança, reprodutibilidade e evidências verificáveis.

## Antes de começar

- abra uma issue ou discussão para mudanças de escopo relevante;
- leia os ADRs em [`docs/architecture`](docs/architecture/);
- não inclua SQL de produção, credenciais, identificadores de negócio ou dados pessoais;
- não proponha automação que execute DDL/DML no target monitorado.

## Ambiente local

É necessário JDK 21. Execute a validação principal com:

```bash
./mvnw verify
```

No Windows, use `mvnw.cmd verify`.

Os laboratórios que usam PostgreSQL podem exigir Docker. Eles são complementares à suíte unitária e não devem alterar uma instância de banco fora do ambiente isolado criado pelo próprio teste.

## Diretrizes para mudanças

1. Preserve a separação entre domínio, aplicação, adapters e bootstrap.
2. Mantenha decisões de segurança *fail-closed*.
3. Registre evidências e transições de estado de forma auditável.
4. Acrescente ou atualize testes na mesma alteração funcional.
5. Atualize documentação, ADR ou protocolo experimental quando necessário.
6. Escreva mensagens de commit claras e mantenha a alteração pequena e revisável.

## Pull requests

Descreva o problema, a solução, os testes executados e quaisquer limites conhecidos. Para alterações visuais, inclua capturas de tela em claro e escuro quando aplicável. Para alterações de experimento, informe versão, *seed*, ambiente e artefatos produzidos.

## Código de conduta

Espera-se comunicação respeitosa, revisão baseada em evidências e atenção especial à segurança de dados.
