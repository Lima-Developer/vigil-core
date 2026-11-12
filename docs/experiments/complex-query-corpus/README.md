# Execução inicial do acervo complexo — 12/11/2026

Corpus v1; alternativas manuais, sem chamada a LLM. Validação em PostgreSQL 16.4 (aarch64),
container sem rede, 2 CPUs, RAM 1 GiB, tmpfs 512 MiB. Escala 1, cache aquecido.

- Seed 42: 10/10 pares equivalentes, expectativas de borda aprovadas, três repetições por variante.
- Seed 43: 10/10 pares equivalentes e expectativas aprovadas; uma repetição, usada como checagem funcional.
- Cada seed foi recriado em duas execuções independentes; os hashes de conteúdo coincidiram para o mesmo seed.
- 80 planos medidos completos preservados, além dos relatórios; aquecimentos não entram nas estatísticas.

## Medianas preliminares — seed 42

Variação = (original − candidata) / original. Valores negativos indicam candidata mais lenta.
Amostra pequena: resultados exploratórios, sem inferência de significância ou prova da tese.

| Query | Linhas retornadas | Original (ms) | Candidata (ms) | Variação |
|---|---:|---:|---:|---:|
| Q01 | 301 | 2.736 | 3.521 | -28.7% |
| Q02 | 500 | 168.286 | 2.616 | +98.4% |
| Q03 | 3 | 3.960 | 5.541 | -39.9% |
| Q04 | 3 | 39.484 | 16.621 | +57.9% |
| Q05 | 121 | 17.112 | 17.469 | -2.1% |
| Q06 | 41 | 0.571 | 0.327 | +42.7% |
| Q07 | 900 | 2.649 | 2.264 | +14.5% |
| Q08 | 2000 | 29.359 | 5.082 | +82.7% |
| Q09 | 3 | 2.501 | 1.022 | +59.1% |
| Q10 | 364 | 1.334 | 0.557 | +58.2% |

Q05 executa SQL idêntico nas duas variantes. Sua diferença quantifica ruído nesta execução, não otimização.
Regressões permanecem no acervo: antecipar filtros, trocar sintaxe ou mudar a estratégia de join não garante melhoria.

## Evidências reproduzíveis

- [Relatório seed 42](seed-42/report.json): tempos individuais, hashes e arquivos de cada plano.
- [Relatório seed 43](seed-43/report.json): segunda distribuição.
- [Catálogo e instruções](../../../tools/complex-query-lab/README.md).

Hashes consolidados:

- Seed 42: `860385a1cba2ad3fc9397b2158ded8cdbefe61d09da8da053b8dc8da71b45bd3`
- Seed 43: `fb4afec65e24f51dc2176dffab98747ca6651692a0f8ee51216c507f22737ede`

Os planos representam somente o laboratório sintético. Não há captura de banco real, sugestões de IA ou tokens medidos.
Não foi alegado ganho universal, nem custo em dinheiro. O controle, as regressões e a variabilidade devem acompanhar qualquer apresentação destes números.
