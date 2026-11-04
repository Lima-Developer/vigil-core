package com.vigil.adapters.postgres;

import com.vigil.application.exception.PlanCollectionRejected;
import com.vigil.application.exception.PlanRejectionReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative single-statement policy. No raw input is sent to JDBC before this scan completes. */
final class PostgresSelectStatementGuard {
    static final int MAX_LENGTH = 8192;

    private PostgresSelectStatementGuard() {
    }

    static String validate(String text) {
        if (text == null || text.isBlank()) throw reject(PlanRejectionReason.EMPTY);
        if (text.length() > MAX_LENGTH) throw reject(PlanRejectionReason.TOO_LARGE);
        if (text.indexOf(';') >= 0) throw reject(PlanRejectionReason.MULTIPLE_STATEMENTS);
        if (text.indexOf('\0') >= 0) throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
        List<String> words = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i < length;) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < length && text.charAt(i + 1) == '-') {
                i += 2;
                while (i < length && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                i += 2;
                int depth = 1;
                while (i < length && depth > 0) {
                    if (i + 1 < length && text.charAt(i) == '/' && text.charAt(i + 1) == '*') { depth++; i += 2; }
                    else if (i + 1 < length && text.charAt(i) == '*' && text.charAt(i + 1) == '/') { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
                continue;
            }
            if (words.isEmpty() && !isWord(c)) throw reject(PlanRejectionReason.NOT_SELECT);
            if (c == '\'' || c == '"') {
                char quote = c;
                boolean escapes = c == '\'' && i > 0 && (text.charAt(i - 1) == 'E' || text.charAt(i - 1) == 'e')
                        && (i == 1 || !isIdentifierPart(text.charAt(i - 2)));
                i++;
                boolean closed = false;
                while (i < length) {
                    char current = text.charAt(i++);
                    if (escapes && current == '\\' && i < length) { i++; continue; }
                    if (current == quote) {
                        if (i < length && text.charAt(i) == quote) i++;
                        else { closed = true; break; }
                    }
                }
                if (!closed) throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
                continue;
            }
            if (c == '$') {
                if (i + 1 < length && Character.isDigit(text.charAt(i + 1)))
                    throw reject(PlanRejectionReason.PLACEHOLDERS_UNSUPPORTED);
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
                if (end < length && text.charAt(end) == '$') {
                    String delimiter = text.substring(i, end + 1);
                    int close = text.indexOf(delimiter, end + 1);
                    if (close < 0) throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
                    i = close + delimiter.length();
                    continue;
                }
                i++;
                continue;
            }
            if (isWord(c)) {
                int end = i + 1;
                // PostgreSQL allows '$' after an identifier starts; it cannot open a dollar quote here.
                while (end < length && isIdentifierPart(text.charAt(end))) end++;
                words.add(text.substring(i, end).toUpperCase(Locale.ROOT));
                i = end;
                continue;
            }
            i++;
        }
        if (words.isEmpty() || !words.getFirst().equals("SELECT")) throw reject(PlanRejectionReason.NOT_SELECT);
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            if (word.equals("INTO")) throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
            if (word.equals("FOR") && i + 1 < words.size()) {
                String next = words.get(i + 1);
                if (next.equals("UPDATE") || next.equals("SHARE")
                        || i + 3 < words.size() && next.equals("NO") && words.get(i + 2).equals("KEY") && words.get(i + 3).equals("UPDATE")
                        || i + 2 < words.size() && next.equals("KEY") && words.get(i + 2).equals("SHARE"))
                    throw reject(PlanRejectionReason.FORBIDDEN_CLAUSE);
            }
        }
        return text.trim();
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isWord(c) || c == '$';
    }

    private static PlanCollectionRejected reject(PlanRejectionReason reason) {
        return new PlanCollectionRejected(reason);
    }
}
