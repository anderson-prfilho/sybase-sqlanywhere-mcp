# Servidor MCP Sybase SQL Anywhere

Servidor [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) open source para **SAP SQL Anywhere**, pensado para uso com agentes de IA (Cursor, Claude Desktop, Claude Code e similares).

O servidor é **agnóstico ao driver JDBC**: você aponta o `.jar` e a classe no arquivo `.prp`, sem recompilar. Funciona com:

- **Driver nativo SAP** (`sajdbc4.jar`) — recomendado no Windows quando o SQL Anywhere já está instalado; suporte validado até **SAP SQL Anywhere 17** com `sajdbc4`;
- **Driver jConnect** (`jconn4.jar`) — Java puro, sem dependências nativas.

Baseado originalmente no [framework MCP da CData](https://github.com/cdatasoftware/sap-sybase-mcp-server-by-cdata) (licença MIT) e no repositório [edurbs/sybase-sqlanywhere-mcp](https://github.com/edurbs/sybase-sqlanywhere-mcp).

## Sobre este fork

Este repositório ([anderson-prfilho/sybase-sqlanywhere-mcp](https://github.com/anderson-prfilho/sybase-sqlanywhere-mcp)) expande o upstream, que expunha basicamente `get_tables`, `get_columns` e `run_query`, para um **MCP completo e seguro para bases SAP em produção**.

As mudanças principais (releases **2.0** e **2.1**) foram desenvolvidas para cenários reais com **SQL Anywhere 17** e driver nativo `sajdbc4`, onde o agente precisa:

- explorar catálogo, relacionamentos, procedures, views e permissões sem adivinhar o schema;
- perfilar colunas (cardinalidade, nulos, distribuição de valores) antes de montar JOINs;
- executar consultas com **parâmetros vinculados**, limites de linhas e timeout;
- operar em **modo somente leitura** por padrão, com DML/DDL apenas sob opt-in explícito.

O harness `TestAllTools` valida as **23 ferramentas** contra um banco vivo (resultado documentado: **24/24 PASS**).

Detalhes completos em [CHANGELOG.md](./CHANGELOG.md).

## Novidades

### 2.1

- **7 ferramentas novas:** `ping`, `list_schemas`, `describe_query`, `run_scalar`, `get_column_stats`, `get_value_distribution`, `get_table_permissions`;
- **Parâmetros vinculados** em `run_query` e `execute` (array `params` → `PreparedStatement` com `?`);
- **Compatibilidade SAP SQL Anywhere 17** (catálogos `SYS` ajustados onde colunas como `proc_type` ou `role` não existem);
- **Harness de testes** `TestAllTools` — exercita cada tool com schema/tabela/coluna descobertos automaticamente no banco.

### 2.0

- **13 ferramentas novas** de descoberta, amostragem, plano de execução e escrita opt-in;
- Modo **somente leitura** por padrão; `execute` só com `AllowWrite=true` + `confirm=true` por chamada;
- Listas **allow/block** de schemas; saídas **CSV, JSON e Markdown** com envelope de metadados;
- Senhas **mascaradas** em logs e erros JDBC; **prepared statements** em consultas de metadados.

## Recursos

- Conexão via qualquer driver JDBC configurado no `.prp` — troca de driver sem recompilar.
- **23 ferramentas** cobrindo catálogo, relacionamentos, código-fonte de procedures/views, amostragem, contagem, profiling, permissões, planos de execução e `SELECT` ad hoc (com bind params).
- **Somente leitura por padrão.** A tool `sybase_execute` só é registrada com `AllowWrite=true`, e cada chamada exige `confirm=true`.
- Saída em **CSV, JSON ou Markdown**, com envelope `// meta: {...}` (`rowCount`, `truncated`, `elapsedMs`, tipos das colunas).
- Listas **AllowSchemas / BlockSchemas** para manter o agente nos schemas de negócio.
- **Teto de linhas** (`MaxRows`) e **timeout** (`QueryTimeoutSeconds`) no servidor — protegem o banco e a janela de contexto do LLM.
- **Prepared statements** nas consultas de metadados e suporte a `params` em `run_query`/`execute`.
- Senhas **mascaradas** em logs e mensagens de erro JDBC.

## Pré-requisitos

- **Java 17+** (build e runtime)
- Um dos drivers:
  - SAP SQL Anywhere **16+** instalado (fornece `Java\sajdbc4.jar` e `dbjdbc16.dll`). O instalador costuma colocar `Bin64` no `PATH`; se mudou o diretório de instalação, adicione manualmente ou use `-Djava.library.path="<caminho-Bin64>"` no comando `java`, **ou**
  - JAR jConnect (`jconn4.jar`) — sem bibliotecas nativas.

## Início rápido

### 1. Build (ou download)

Build local a partir deste fork:

```bash
git clone https://github.com/anderson-prfilho/sybase-sqlanywhere-mcp.git
cd sybase-sqlanywhere-mcp
mvn clean package -DskipTests
# gera target/sybase-mcp-server-jar-with-dependencies.jar
```

Também é possível usar releases/JAR do [upstream](https://github.com/edurbs/sybase-sqlanywhere-mcp), mas as ferramentas e correções descritas aqui estão neste fork.

### 2. Criar o `.prp`

Copie `sqlanywhere.prp.example` para `sqlanywhere.prp` e edite. Exemplo mínimo com driver nativo no Windows:

```properties
Prefix=sybase
DriverPath=C:\\Program Files\\SQL Anywhere 16\\Java\\sajdbc4.jar
DriverClass=sybase.jdbc4.sqlanywhere.IDriver
JdbcUrl=jdbc:sqlanywhere:links=tcpip(host=HOST);ServerName=srv;port=PORT;DatabaseName=mydb
User=myuser
Password=mypass
ReadOnly=true
```

O arquivo `.prp` está no `.gitignore` (não versione credenciais). Veja [Opções de configuração](#opções-de-configuração).

### 3. Registrar no cliente MCP

Cursor / Claude Code (`~/.cursor/mcp.json` ou `.mcp.json` no projeto):

```json
{
  "mcpServers": {
    "sybase-sqlanywhere": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "C:\\caminho\\para\\sybase-mcp-server-jar-with-dependencies.jar",
        "C:\\caminho\\para\\sqlanywhere.prp"
      ]
    }
  }
}
```

Claude Desktop (`claude_desktop_config.json`): remova `"type": "stdio"`; o restante é igual.

Exemplo completo: [mcp.json.example](./mcp.json.example).

## Ferramentas disponíveis

Todos os nomes são prefixados pelo `Prefix` do `.prp` (padrão `sybase`). Substitua `sybase` abaixo se usar outro prefixo.

| Ferramenta | Finalidade |
|---|---|
| `sybase_ping` | Health check (`SELECT 1`) |
| `sybase_database_info` | Produto, versão, charset, collation, usuário atual, parâmetros de runtime |
| `sybase_list_schemas` | Schemas/owners distintos com contagem de objetos |
| `sybase_get_tables` | Tabelas/views com `pattern`, `type`, `limit`, `offset`, `format` |
| `sybase_get_columns` | Colunas (nome, tipo, tamanho, escala, default, nullable, remarks) |
| `sybase_get_table_info` | JSON único: identidade + colunas + PK + FKs + índices + linhas estimadas |
| `sybase_get_primary_keys` | Colunas da PK em ordem |
| `sybase_get_foreign_keys` | FKs de saída e/ou entrada |
| `sybase_get_indexes` | Índices com flag unique e ordem das colunas |
| `sybase_get_table_permissions` | Privilégios por grantee (select/insert/update/delete/…) |
| `sybase_get_procedures` | Procedures e functions (tipo inferido em SA 17) |
| `sybase_get_procedure_definition` | Código-fonte (corpo CREATE) |
| `sybase_get_view_definition` | Corpo SELECT da view |
| `sybase_search_objects` | Busca LIKE em tabelas/views/procedures/functions/colunas |
| `sybase_sample_rows` | TOP N da tabela, opcionalmente aleatório |
| `sybase_count_rows` | `COUNT(*)` com `WHERE` opcional |
| `sybase_get_column_stats` | Perfil: total, nulos, distintos, min/max |
| `sybase_get_value_distribution` | Top N valores mais frequentes com contagem e percentual |
| `sybase_describe_query` | Descreve um SELECT sem executar (`sa_describe_query`) |
| `sybase_explain_plan` | Plano de execução (texto ou XML), com fallback automático de engine |
| `sybase_run_scalar` | Primeira coluna da primeira linha de um SELECT |
| `sybase_run_query` | SELECT ad hoc (rejeita DML/DDL em read-only). `params` opcional para `?`. |
| `sybase_execute` | INSERT/UPDATE/DELETE/DDL. Só com `AllowWrite=true` + `confirm=true` por chamada. `params` opcional. |

Toda ferramenta tabular aceita `format` (`csv` / `json` / `markdown`) e retorna na primeira linha o envelope `// meta: {...}` com `rowCount`, `elapsedMs`, `truncated`, tipos e `warning` opcional.

### Fluxo recomendado para agentes

1. `sybase_database_info` — contexto do servidor; depois `sybase_list_schemas` para saber onde buscar.
2. `sybase_search_objects` ou `sybase_get_tables` com `pattern=` para localizar objetos.
3. `sybase_get_table_info` na tabela candidata — colunas, chaves e FKs em um único JSON para planejar JOINs.
4. `sybase_sample_rows` para exemplos; `sybase_get_value_distribution` em colunas-chave para cardinalidade e valores enumerados.
5. `sybase_describe_query` para validar forma/tipos **sem executar** o SELECT.
6. `sybase_run_query` (com `params` quando houver filtros) — use `format=json` para valores tipados ou `format=markdown` para respostas legíveis.
7. `sybase_explain_plan` se precisar sugerir índice ou reescrita de query.

## Testes

O repositório inclui o harness Java `io.github.eduardo.sybasemcp.cli.TestAllTools`, que carrega o `.prp`, instancia cada ferramenta, descobre schema/tabela/coluna reais no banco e imprime resumo PASS/FAIL/SKIP.

```bash
java -cp target/sybase-mcp-server-jar-with-dependencies.jar ^
     io.github.eduardo.sybasemcp.cli.TestAllTools sqlanywhere.prp
```

No Linux/macOS, troque `^` por `\` na continuação da linha.

Encerra com código diferente de zero se alguma ferramenta falhar. Útil após trocar driver, mudar de base ou alterar código das tools.

## Opções de configuração

Todas as chaves ficam no `.prp` (arquivo de propriedades Java). Exemplo comentado: [sqlanywhere.prp.example](./sqlanywhere.prp.example).

| Chave | Obrigatório | Padrão | Finalidade |
|---|:---:|---|---|
| `Prefix` | sim | — | Prefixo dos nomes das tools (`<prefix>_get_tables`…) |
| `DriverPath` | sim | — | Caminho do `.jar` do driver JDBC |
| `DriverClass` | sim | — | Nome qualificado da classe do driver |
| `JdbcUrl` | sim | — | URL JDBC do banco |
| `User` | não | — | Usuário (preferível a embutir na URL) |
| `Password` | não | — | Senha (mascarada nos logs) |
| `ReadOnly` | não | `true` | Se `true`, `run_query` só aceita SELECT/WITH/EXPLAIN/DESCRIBE |
| `AllowWrite` | não | `false` | Se `true`, registra `<prefix>_execute` para DML/DDL |
| `MaxRows` | não | `1000` | Teto de linhas retornadas por qualquer tool |
| `QueryTimeoutSeconds` | não | `30` | Timeout de query no servidor |
| `DefaultFormat` | não | `csv` | Formato padrão (`csv`, `json`, `markdown`) |
| `AllowSchemas` | não | (todos) | Lista allow (separada por vírgula, case-insensitive) |
| `BlockSchemas` | não | `SYS,DBO,RS_SYSTABGROUP,PUBLIC` | Lista deny (separada por vírgula) |
| `Tables` | não | — | Tabelas expostas como recursos MCP (opcional) |
| `LogFile` | não | (stderr) | Arquivo de log |

## Notas sobre drivers

### Driver nativo (`sajdbc4`) no Windows

O driver nativo carrega `dbjdbc16.dll` de `<SQL Anywhere 16>\Bin64`. O instalador SAP costuma colocar essa pasta no `PATH`. Se aparecer `UnsatisfiedLinkError`:

- adicione `<SQL Anywhere 16>\Bin64` ao `PATH`, ou
- inclua `-Djava.library.path="C:\\Program Files\\SQL Anywhere 16\\Bin64"` no comando `java` do `mcp.json`.

O driver nativo aceita nomes de tabela sem qualificação; **não** é necessária a regra TDS do jConnect de sempre usar `owner.tabela`.

**SQL Anywhere 17:** consultas de catálogo foram ajustadas para colunas inexistentes em SA 17 (por exemplo `SYS.SYSPROCEDURE.proc_type`, `SYS.SYSFKEY.role`); tipos de rotina e nomes de FK usam alternativas documentadas no [CHANGELOG.md](./CHANGELOG.md).

### jConnect (`jconn4.jar`)

Java puro, funciona em qualquer SO. Com jConnect (protocolo TDS):

- **Sempre** qualifique tabelas com o owner: `SELECT * FROM owner.tbl`;
- **Não** use identificadores entre aspas duplas (`"tbl"`).

Use `_get_tables` primeiro para descobrir o owner correto (coluna `Schema`).

## Modelo de segurança

- **Somente leitura por padrão.** Com `ReadOnly=true` (padrão), `run_query` só aceita statements do tipo SELECT. Para DML/DDL, defina `AllowWrite=true` no `.prp` **e** chame `<prefix>_execute` com `confirm=true` em cada statement.
- **Allow/block de schemas.** Por padrão o agente não enxerga `SYS`, `DBO`, `RS_SYSTABGROUP` nem `PUBLIC`. Ajuste com `BlockSchemas=` ou restrinja com `AllowSchemas=`.
- **Sem statements empilhados.** `run_query` e `execute` rejeitam payloads com mais de um statement.
- **Limites.** Todas as tools respeitam `MaxRows` e `QueryTimeoutSeconds`; parâmetros por chamada podem **reduzir**, nunca aumentar, o teto do servidor.
- **Logs mascarados.** Valores de `Password`, `PWD` e `passwd` são removidos de logs e exceções JDBC antes de serem emitidos.
- **Prepared statements.** Metadados usam parâmetros vinculados. Tools que montam SQL a partir de identificadores (`sample_rows`, `count_rows`, etc.) exigem padrão `[A-Za-z_][A-Za-z0-9_$#]*` antes de interpolar.

## Upstream e contribuição

| Repositório | Papel |
|---|---|
| [edurbs/sybase-sqlanywhere-mcp](https://github.com/edurbs/sybase-sqlanywhere-mcp) | Projeto original |
| [anderson-prfilho/sybase-sqlanywhere-mcp](https://github.com/anderson-prfilho/sybase-sqlanywhere-mcp) | Fork com expansão v2.x (este repositório) |

Para sincronizar com o upstream:

```bash
git fetch upstream
git merge upstream/main
```

## Licença

MIT — veja [LICENSE](./LICENSE).
