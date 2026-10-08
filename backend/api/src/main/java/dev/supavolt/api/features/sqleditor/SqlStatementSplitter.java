package dev.supavolt.api.features.sqleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits SQL into statements while respecting string literals, dollar quoting, identifiers and
 * comments. The original split on ';', so 'SELECT ...; -- x' and any literal containing a
 * semicolon were mis-counted.
 */
public final class SqlStatementSplitter {

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    private SqlStatementSplitter() {
    }

    public static List<String> split(String sql) {
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        var tag = DOLLAR_TAG.matcher(sql);
        var i = 0;

        while (i < sql.length()) {
            var c = sql.charAt(i);

            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }

            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, sql.length());
                continue;
            }

            if (c == '\'' || c == '"') {
                current.append(c);
                i++;
                while (i < sql.length()) {
                    current.append(sql.charAt(i));
                    if (sql.charAt(i) == c) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                            current.append(sql.charAt(++i));
                            i++;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            if (c == '$') {
                // A dollar-quote tag is $$ or $name$. Anything else ($1 parameters, a$b
                // identifiers) is ordinary text, or "$1, $2; drop" would swallow the semicolon.
                tag.region(i, sql.length());
                if (!tag.lookingAt()) {
                    current.append(c);
                    i++;
                    continue;
                }

                var open = tag.group();
                var close = sql.indexOf(open, i + open.length());
                if (close < 0) {
                    current.append(sql, i, sql.length());
                    i = sql.length();
                    continue;
                }

                current.append(sql, i, close + open.length());
                i = close + open.length();
                continue;
            }

            if (c == ';') {
                add(statements, current);
                current.setLength(0);
                i++;
                continue;
            }

            current.append(c);
            i++;
        }

        add(statements, current);
        return statements;
    }

    private static void add(List<String> statements, StringBuilder current) {
        var statement = current.toString().strip();
        if (!statement.isEmpty()) statements.add(statement);
    }
}
