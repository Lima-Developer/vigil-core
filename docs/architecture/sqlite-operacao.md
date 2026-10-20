# Operação do SQLite local (VIG-024)

Defina `VIGIL_DATABASE_PATH` como caminho **absoluto** em um volume local persistente, por exemplo `/var/lib/vigil/vigil.db`. Monte o diretório antes de iniciar. O processo precisa criar e remover arquivos nele, escrever no arquivo do banco quando existir e dispor de pelo menos `VIGIL_STORAGE_MIN_FREE_BYTES` bytes livres (padrão: `268435456`, 256 MiB). Valor ausente para o caminho ou mínimo não positivo causa erro de configuração (saída 2); falha de storage causa health ruim (saída 1). O health imprime a causa e o caminho, sem stack trace.

Cada conexão usa `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000` ms, `wal_autocheckpoint=1000` páginas e `synchronous=FULL`. O WAL permite que leitores vejam dados confirmados enquanto um escritor está ativo. Apenas um escritor por vez é admitido. SQLite pode manter arquivos `vigil.db-wal` e `vigil.db-shm` ao lado do banco. O checkpoint automático ocorre após aproximadamente mil páginas WAL; não apague esses arquivos com a aplicação em execução. O health abre o banco existente e executa `PRAGMA quick_check` sob demanda na inicialização. A abertura pela fábrica executa `PRAGMA journal_mode=WAL`: em um banco já em WAL não há mudança de modo, mas a abertura tem esse efeito colateral. O `quick_check` apenas lê. A verificação custa I/O proporcional ao banco e não deve ser chamada a cada requisição.

## Backup consistente

Use `SqliteBackup.backupTo(destino)` apontando para um caminho novo. O espaço exigido é o tamanho do arquivo principal do banco (sem o WAL) mais uma margem igual ao maior valor entre 10% desse tamanho e 1 MiB. Essa checagem inicial não reserva espaço. A operação usa `VACUUM INTO` para produzir um arquivo único com uma visão confirmada, executa `quick_check`, verifica as tabelas `analysis_case` e `case_event` e `foreign_key_check`, e só então move o arquivo para o destino. A verificação abre o backup pela fábrica, que aplica `journal_mode=WAL`; portanto, o arquivo entregue também fica configurado em WAL. Em falha, remove apenas o arquivo temporário criado pela própria operação. Destino existente é recusado e preservado. Não copie somente `vigil.db` enquanto há escrita: transações confirmadas podem estar no WAL.

## Restauração

1. Pare o processo que escreve no banco e guarde o volume antigo.
2. Copie **somente o arquivo do backup** para um novo caminho em um volume persistente e gravável. Não use arquivos `-wal` ou `-shm` da origem.
3. Aponte `VIGIL_DATABASE_PATH` para o arquivo restaurado, inicie o processo e verifique o health (`quick_check=ok`). Confira as contagens de `analysis_case` e `case_event` conforme o registro do backup.
4. Retome a operação apenas após essa conferência.

O SQLite local não fornece HA, failover ou múltiplos escritores. Não compartilhe o banco por NFS/EFS ou outro filesystem de rede. A sonda verifica permissões e espaço disponível no instante da chamada; ela não consegue identificar de forma confiável se o filesystem é efêmero, garantir persistência após perda do host ou reservar espaço para escritas futuras. A montagem de volume persistente, monitoramento de capacidade e retenção externa de backups pertencem à operação.
