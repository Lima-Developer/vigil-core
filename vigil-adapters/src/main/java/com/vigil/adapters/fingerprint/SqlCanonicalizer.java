package com.vigil.adapters.fingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes authorized SQL into the stable {@code v1} canonical form the fingerprint is computed from. The form keeps
 * query structure but carries no literal values: every constant becomes a positional placeholder ({@code $1, $2, ...})
 * in order of appearance. The canonicalizer is fail-closed — it throws {@link UnsupportedSqlException} rather than
 * guessing — so a fingerprint is only ever derived from SQL it fully understands. The raw SQL is never hashed as a
 * fallback.
 *
 * <h2>v1 rules</h2>
 * <ul>
 *   <li>Comments {@code -- ...} to end of line and block comments {@code /* ... *}{@code /} (nested, as in PostgreSQL)
 *       are removed.</li>
 *   <li>Every literal becomes a positional placeholder: numbers (integer, decimal, scientific), {@code '...'} strings
 *       (with {@code ''} escaping), {@code E'...'} strings (with backslash escaping), dollar-quoted strings
 *       ({@code $$...$$} and {@code $tag$...$tag$}), {@code TRUE}/{@code FALSE}, and the value of typed literals
 *       ({@code DATE '2024-01-01'} becomes {@code DATE $n}). {@code NULL} is kept as a keyword, not a placeholder.</li>
 *   <li>Parameters already present in the text ({@code $1, $2, ...}) are renumbered into the same sequence.</li>
 *   <li>A list made up only of constants inside {@code IN (...)} collapses to a single placeholder, so
 *       {@code IN (1,2,3)} and {@code IN (7)} share a fingerprint. This is a deliberate decision: the <em>arity</em> of
 *       a constant {@code IN} list is treated as an irrelevant literal, like the values themselves. A list containing
 *       anything that is not a constant (a column, a subquery) is left untouched.</li>
 *   <li>{@code LIMIT}/{@code OFFSET} numbers follow the general rule and become placeholders.</li>
 *   <li>Keywords are upper-cased; unquoted identifiers are lower-cased (PostgreSQL folds unquoted identifiers);
 *       double-quoted identifiers keep their exact case (and their quotes, so {@code "Orders"} differs from
 *       {@code orders}).</li>
 *   <li>Whitespace between tokens collapses to a single space and a trailing {@code ;} is removed.</li>
 * </ul>
 *
 * <p>Rejected as unsupported (fail-closed): more than one statement, an unterminated string, comment or dollar-quote,
 * an empty statement, or any character the lexer does not recognize.
 *
 * <p>Note: keyword casing is decided by the token's lower-cased form, so the same word maps to the same output
 * regardless of its input case even if it is not in the (necessarily partial) keyword set — which is why case never
 * changes the fingerprint. The set only governs upper- versus lower-casing cosmetics of the canonical text.
 */
public final class SqlCanonicalizer {

    private static final String OPERATOR_CHARS = "+-*/<>=~!@#%^&|?:";

    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "in", "is", "like", "ilike", "similar", "between",
            "as", "on", "join", "inner", "left", "right", "full", "outer", "cross", "natural", "using", "lateral",
            "group", "by", "order", "having", "limit", "offset", "distinct", "union", "all", "except", "intersect",
            "insert", "into", "values", "update", "set", "delete", "with", "recursive", "case", "when", "then",
            "else", "end", "asc", "desc", "exists", "any", "some", "over", "partition", "window", "fetch", "first",
            "next", "row", "rows", "only", "returning", "cast", "array", "default", "date", "time", "timestamp",
            "timestamptz", "interval", "at", "zone");

    private enum Kind { WORD, CONSTANT, PUNCT }

    /** For {@link Kind#CONSTANT} the text stays {@code null} until placeholders are numbered after the IN-collapse. */
    private record Token(Kind kind, String text) { }

    public String canonicalize(String sql) {
        if (sql == null) {
            throw new UnsupportedSqlException("SQL ausente.");
        }
        List<Token> tokens = lex(sql);
        if (tokens.isEmpty()) {
            throw new UnsupportedSqlException("Instrução vazia.");
        }
        collapseConstantInLists(tokens);
        return render(tokens);
    }

    private List<Token> lex(String sql) {
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        boolean statementEnded = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            // Anything other than whitespace or a comment after a ';' is a second statement.
            if (statementEnded) {
                throw new UnsupportedSqlException("Mais de uma instrução não é suportada.");
            }
            if (c == ';') {
                statementEnded = true;
                i++;
                continue;
            }
            if (c == '\'') {
                tokens.add(new Token(Kind.CONSTANT, null));
                i = readQuotedString(sql, i, false);
                continue;
            }
            if ((c == 'E' || c == 'e') && i + 1 < n && sql.charAt(i + 1) == '\'') {
                tokens.add(new Token(Kind.CONSTANT, null));
                i = readQuotedString(sql, i + 1, true);
                continue;
            }
            if (c == '"') {
                i = readQuotedIdentifier(sql, i, tokens);
                continue;
            }
            if (c == '$') {
                i = readDollar(sql, i, tokens);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                i = readWord(sql, i, tokens);
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                tokens.add(new Token(Kind.CONSTANT, null));
                i = readNumber(sql, i);
                continue;
            }
            if (c == '(' || c == ')' || c == ',' || c == '.') {
                tokens.add(new Token(Kind.PUNCT, String.valueOf(c)));
                i++;
                continue;
            }
            if (OPERATOR_CHARS.indexOf(c) >= 0) {
                int j = i;
                while (j < n && OPERATOR_CHARS.indexOf(sql.charAt(j)) >= 0) {
                    j++;
                }
                tokens.add(new Token(Kind.PUNCT, sql.substring(i, j)));
                i = j;
                continue;
            }
            throw new UnsupportedSqlException("Token não reconhecido.");
        }
        return tokens;
    }

    private int readWord(String sql, int start, List<Token> tokens) {
        int n = sql.length();
        int j = start;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '$')) {
            j++;
        }
        String lower = sql.substring(start, j).toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false")) {
            tokens.add(new Token(Kind.CONSTANT, null));
        } else if (lower.equals("null")) {
            tokens.add(new Token(Kind.WORD, "NULL"));
        } else if (KEYWORDS.contains(lower)) {
            tokens.add(new Token(Kind.WORD, lower.toUpperCase(Locale.ROOT)));
        } else {
            tokens.add(new Token(Kind.WORD, lower));
        }
        return j;
    }

    private int readNumber(String sql, int start) {
        int n = sql.length();
        int j = start;
        while (j < n && Character.isDigit(sql.charAt(j))) {
            j++;
        }
        if (j < n && sql.charAt(j) == '.') {
            j++;
            while (j < n && Character.isDigit(sql.charAt(j))) {
                j++;
            }
        }
        if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
                k++;
            }
            if (k < n && Character.isDigit(sql.charAt(k))) {
                k++;
                while (k < n && Character.isDigit(sql.charAt(k))) {
                    k++;
                }
                j = k;
            }
        }
        return j;
    }

    /** {@code start} points at the opening quote. Returns the index just past the closing quote. */
    private int readQuotedString(String sql, int start, boolean escapeString) {
        int n = sql.length();
        int j = start + 1;
        while (j < n) {
            char c = sql.charAt(j);
            if (escapeString && c == '\\') {
                j += 2;
                continue;
            }
            if (c == '\'') {
                if (j + 1 < n && sql.charAt(j + 1) == '\'') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        throw new UnsupportedSqlException("String não terminada.");
    }

    private int readQuotedIdentifier(String sql, int start, List<Token> tokens) {
        int n = sql.length();
        int j = start + 1;
        StringBuilder content = new StringBuilder();
        while (j < n) {
            char c = sql.charAt(j);
            if (c == '"') {
                if (j + 1 < n && sql.charAt(j + 1) == '"') {
                    content.append('"');
                    j += 2;
                    continue;
                }
                // Keep the quotes in the canonical text so a quoted identifier is distinct from an unquoted one.
                tokens.add(new Token(Kind.WORD, "\"" + content + "\""));
                return j + 1;
            }
            content.append(c);
            j++;
        }
        throw new UnsupportedSqlException("Identificador entre aspas não terminado.");
    }

    private int readDollar(String sql, int start, List<Token> tokens) {
        int n = sql.length();
        if (start + 1 < n && Character.isDigit(sql.charAt(start + 1))) {
            int j = start + 1;
            while (j < n && Character.isDigit(sql.charAt(j))) {
                j++;
            }
            tokens.add(new Token(Kind.CONSTANT, null));
            return j;
        }
        int tagEnd = matchDollarTag(sql, start);
        if (tagEnd < 0) {
            throw new UnsupportedSqlException("Token não reconhecido.");
        }
        String delimiter = sql.substring(start, tagEnd);
        int close = sql.indexOf(delimiter, tagEnd);
        if (close < 0) {
            throw new UnsupportedSqlException("Dollar-quote não terminado.");
        }
        tokens.add(new Token(Kind.CONSTANT, null));
        return close + delimiter.length();
    }

    /** At {@code start} (a {@code $}), matches an opening {@code $tag$} delimiter and returns the index after it. */
    private int matchDollarTag(String sql, int start) {
        int n = sql.length();
        int j = start + 1;
        if (j < n && sql.charAt(j) == '$') {
            return j + 1;
        }
        if (j < n && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
            while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                j++;
            }
            if (j < n && sql.charAt(j) == '$') {
                return j + 1;
            }
        }
        return -1;
    }

    private int skipBlockComment(String sql, int start) {
        int n = sql.length();
        int depth = 0;
        int j = start;
        while (j < n) {
            if (j + 1 < n && sql.charAt(j) == '/' && sql.charAt(j + 1) == '*') {
                depth++;
                j += 2;
                continue;
            }
            if (j + 1 < n && sql.charAt(j) == '*' && sql.charAt(j + 1) == '/') {
                depth--;
                j += 2;
                if (depth == 0) {
                    return j;
                }
                continue;
            }
            j++;
        }
        throw new UnsupportedSqlException("Comentário não terminado.");
    }

    /** Collapses {@code IN ( const [, const]* )} to {@code IN ( $n )}; leaves any non-constant list untouched. */
    private void collapseConstantInLists(List<Token> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!(tokens.get(i).kind() == Kind.WORD && "IN".equals(tokens.get(i).text()))) {
                continue;
            }
            if (!isPunct(tokens.get(i + 1), "(")) {
                continue;
            }
            int open = i + 1;
            int j = open + 1;
            boolean expectConstant = true;
            int close = -1;
            boolean onlyConstants = true;
            while (j < tokens.size()) {
                Token token = tokens.get(j);
                if (isPunct(token, ")")) {
                    close = j;
                    break;
                }
                if (expectConstant) {
                    if (token.kind() == Kind.CONSTANT) {
                        expectConstant = false;
                        j++;
                        continue;
                    }
                    onlyConstants = false;
                    break;
                }
                if (isPunct(token, ",")) {
                    expectConstant = true;
                    j++;
                    continue;
                }
                onlyConstants = false;
                break;
            }
            // Collapse only a non-empty list that is wholly constants and closes right after one (no trailing comma).
            if (close >= 0 && onlyConstants && !expectConstant) {
                tokens.subList(open + 1, close).clear();
                tokens.add(open + 1, new Token(Kind.CONSTANT, null));
            }
        }
    }

    private boolean isPunct(Token token, String text) {
        return token.kind() == Kind.PUNCT && token.text().equals(text);
    }

    private String render(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        int constant = 0;
        for (Token token : tokens) {
            String text = token.kind() == Kind.CONSTANT ? "$" + (++constant) : token.text();
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(text);
        }
        return out.toString();
    }
}
