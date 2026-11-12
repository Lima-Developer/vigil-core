# Execuções do acervo complexo — 12/11/2026

Corpus v1; alternativas manuais, sem chamada a LLM. Validação em PostgreSQL 16.4 (aarch64),
container sem rede, 2 CPUs, RAM 1 GiB, tmpfs 512 MiB e cache aquecido.

- Execução principal: escala 3, seeds 42 e 43, 10/10 pares equivalentes e sete repetições por variante.
- Foram preservados 280 planos medidos completos; aquecimentos não entram nas estatísticas.
- A execução inicial em escala 1 permanece versionada para auditoria: três repetições no seed 42 e uma no seed 43,
  totalizando 80 planos. Ela não é mais a fonte exibida pelo workspace.
- Os hashes de conteúdo permitem conferir reprodutibilidade por seed sem tratar o hash como autenticação.

## Medianas da execução principal — seed 42, escala 3

Variação = (original − candidata) / original. Valores negativos indicam candidata mais lenta.
Amostra pequena: resultados exploratórios, sem inferência de significância ou prova da tese.

| Query | Linhas retornadas | Original (ms) | Candidata (ms) | Variação |
|---|---:|---:|---:|---:|
| Q01 | 901 | 7.664 | 8.968 | -17.0% |
| Q02 | 1.500 | 25.474 | 7.635 | +70.0% |
| Q03 | 3 | 11.509 | 15.979 | -38.8% |
| Q04 | 3 | 126.436 | 54.811 | +56.6% |
| Q05 | 121 | 30.343 | 30.288 | +0.2% |
| Q06 | 41 | 0.562 | 0.333 | +40.7% |
| Q07 | 2.700 | 7.848 | 17.925 | -128.4% |
| Q08 | 6.000 | 117.365 | 14.950 | +87.3% |
| Q09 | 3 | 7.546 | 3.122 | +58.6% |
| Q10 | 1.091 | 3.836 | 1.532 | +60.1% |

Q05 executa SQL idêntico nas duas variantes. Sua diferença quantifica ruído nesta execução, não otimização. Q01
muda de sinal entre os seeds e Q07 regride nos dois, evidenciando que distribuição e estratégia importam. Antecipar
filtros, trocar sintaxe ou mudar a estratégia de join não garante melhoria.

## Evidências reproduzíveis

- [Relatório principal seed 42](scale-3-seed-42/report.json): sete repetições e planos em escala 3.
- [Relatório principal seed 43](scale-3-seed-43/report.json): segunda distribuição nas mesmas condições.
- [Execução inicial seed 42](seed-42/report.json) e [seed 43](seed-43/report.json): evidência histórica em escala 1.
- [Catálogo e instruções](../../../tools/complex-query-lab/README.md).

Hashes consolidados da execução principal:

- Seed 42: `8a69547c5597d5d03f74f691573c86b2f6b0ddd19f7ace588f4ae27373d1cd0d`
- Seed 43: `05596a8e41a9351d1babf43ddc233e6b2d916fd5c220aa42717dfc5d5b09671a`

Os planos representam somente o laboratório sintético. Não há captura de banco real, sugestões de IA ou tokens medidos.
Não foi alegado ganho universal, nem custo em dinheiro. O controle, as regressões e a variabilidade devem acompanhar qualquer apresentação destes números.
