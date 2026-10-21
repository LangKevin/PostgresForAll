package com.postgresforall.core.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements on top-level semicolons, respecting
 * line / block comments (nested), single-quoted strings, quoted identifiers
 * and dollar-quoted bodies.
 */
public final class ScriptSplitter {
    private ScriptSplitter() { }

    public static List<String> split(String script) {
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        var i = 0;
        var n = script.length();

        while (i < n) {
            var c = script.charAt(i);

            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                while (i < n && script.charAt(i) != '\n') {
                    current.append(script.charAt(i));
                    i++;
                }
                continue;
            }

            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                var depth = 1;
                current.append(c).append('*');
                i += 2;
                while (i < n && depth > 0) {
                    if (i + 1 < n && script.charAt(i) == '/' && script.charAt(i + 1) == '*') {
                        depth++;
                        current.append("/*");
                        i += 2;
                    } else if (i + 1 < n && script.charAt(i) == '*' && script.charAt(i + 1) == '/') {
                        depth--;
                        current.append("*/");
                        i += 2;
                    } else {
                        current.append(script.charAt(i));
                        i++;
                    }
                }
                continue;
            }

            if (c == '\'') {
                current.append(c);
                i++;
                while (i < n) {
                    if (script.charAt(i) == '\'' && i + 1 < n && script.charAt(i + 1) == '\'') {
                        current.append("''");
                        i += 2;
                    } else if (script.charAt(i) == '\'') {
                        current.append('\'');
                        i++;
                        break;
                    } else {
                        current.append(script.charAt(i));
                        i++;
                    }
                }
                continue;
            }

            if (c == '"') {
                current.append(c);
                i++;
                while (i < n && script.charAt(i) != '"') {
                    current.append(script.charAt(i));
                    i++;
                }
                if (i < n) {
                    current.append(script.charAt(i));
                    i++;
                }
                continue;
            }

            if (c == '$') {
                var tagEnd = script.indexOf('$', i + 1);
                if (tagEnd > i) {
                    var tag = script.substring(i, tagEnd + 1);
                    var validTag = true;
                    for (var k = 1; k < tag.length() - 1; k++) {
                        var ch = tag.charAt(k);
                        if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                            validTag = false;
                            break;
                        }
                    }

                    if (validTag) {
                        current.append(tag);
                        i = tagEnd + 1;
                        var close = script.indexOf(tag, i);
                        if (close < 0) {
                            current.append(script, i, n);
                            i = n;
                        } else {
                            current.append(script, i, close);
                            current.append(tag);
                            i = close + tag.length();
                        }
                        continue;
                    }
                }
            }

            if (c == ';') {
                var stmt = current.toString().trim();
                if (!stmt.isEmpty()) statements.add(stmt);
                current.setLength(0);
                i++;
                continue;
            }

            current.append(c);
            i++;
        }

        var tail = current.toString().trim();
        if (!tail.isEmpty()) statements.add(tail);
        return statements;
    }
}
