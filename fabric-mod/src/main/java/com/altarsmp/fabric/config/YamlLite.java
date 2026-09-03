package com.altarsmp.fabric.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML reader for the original AltarSMP configuration files.
 *
 * <p>The original plugin used Bukkit's {@code YamlConfiguration}.  Fabric has no
 * YAML library on the classpath and adding SnakeYAML would mean shadowing a
 * third-party dependency into the released jar, which the porting brief
 * explicitly forbids.  Instead this reader implements exactly the YAML subset the
 * AltarSMP {@code config.yml} / {@code s2.yml} files use:</p>
 *
 * <ul>
 *   <li>indentation based nested mappings (spaces)</li>
 *   <li>{@code #} comments (whole line or trailing after whitespace)</li>
 *   <li>inline flow sequences {@code []} and block sequences {@code - value}</li>
 *   <li>single/double quoted scalars and bare scalars</li>
 *   <li>integers, floats, booleans, null and strings</li>
 * </ul>
 *
 * <p>The result is a {@code Map<String, Object>} tree identical in shape to what
 * {@code YamlConfiguration} produced, so every config path used by the original
 * plugin resolves the same way.</p>
 */
public final class YamlLite {

	private YamlLite() {
	}

	public static Map<String, Object> load(String content) throws IOException {
		List<Line> lines = tokenize(content);
		Map<String, Object> root = new LinkedHashMap<>();
		parseMap(lines, new int[]{0}, 0, root);
		return root;
	}

	private record Line(int indent, String content, boolean listItem) {
	}

	private static List<Line> tokenize(String content) throws IOException {
		List<Line> out = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
			String raw;
			while ((raw = reader.readLine()) != null) {
				String line = stripComment(raw);
				if (line.isBlank()) {
					continue;
				}
				int indent = 0;
				while (indent < line.length() && line.charAt(indent) == ' ') {
					indent++;
				}
				if (indent >= line.length()) {
					continue;
				}
				String body = line.substring(indent);
				boolean item = body.startsWith("- ") || body.equals("-");
				if (item) {
					body = body.length() > 1 ? body.substring(2).trim() : "";
				}
				out.add(new Line(indent, body, item));
			}
		}
		return out;
	}

	private static String stripComment(String line) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == '#' && !inSingle && !inDouble) {
				if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
					return line.substring(0, i);
				}
			}
		}
		return line;
	}

	private static void parseMap(List<Line> lines, int[] cursor, int indent, Map<String, Object> target) {
		while (cursor[0] < lines.size()) {
			Line line = lines.get(cursor[0]);
			if (line.indent() < indent) {
				return;
			}
			if (line.indent() > indent) {
				// unexpected deeper indent - treat as part of previous value, skip
				cursor[0]++;
				continue;
			}
			if (line.listItem()) {
				return;
			}
			cursor[0]++;
			int colon = findKeyColon(line.content());
			if (colon < 0) {
				continue;
			}
			String key = unquote(line.content().substring(0, colon).trim());
			String rest = line.content().substring(colon + 1).trim();
			if (!rest.isEmpty()) {
				target.put(key, scalar(rest));
				continue;
			}
			// Nested structure: peek at the next line.
			if (cursor[0] < lines.size()) {
				Line next = lines.get(cursor[0]);
				if (next.indent() > indent) {
					if (next.listItem()) {
						List<Object> list = new ArrayList<>();
						parseList(lines, cursor, next.indent(), list);
						target.put(key, list);
					} else {
						Map<String, Object> child = new LinkedHashMap<>();
						parseMap(lines, cursor, next.indent(), child);
						target.put(key, child);
					}
					continue;
				}
				if (next.indent() == indent && next.listItem()) {
					List<Object> list = new ArrayList<>();
					parseList(lines, cursor, indent, list);
					target.put(key, list);
					continue;
				}
			}
			target.put(key, null);
		}
	}

	private static void parseList(List<Line> lines, int[] cursor, int indent, List<Object> target) {
		while (cursor[0] < lines.size()) {
			Line line = lines.get(cursor[0]);
			if (line.indent() != indent || !line.listItem()) {
				return;
			}
			cursor[0]++;
			if (line.content().isEmpty()) {
				Map<String, Object> child = new LinkedHashMap<>();
				if (cursor[0] < lines.size() && lines.get(cursor[0]).indent() > indent) {
					parseMap(lines, cursor, lines.get(cursor[0]).indent(), child);
				}
				target.add(child);
			} else {
				target.add(scalar(line.content()));
			}
		}
	}

	private static int findKeyColon(String s) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == ':' && !inSingle && !inDouble) {
				if (i + 1 >= s.length() || s.charAt(i + 1) == ' ') {
					return i;
				}
			}
		}
		return -1;
	}

	private static String unquote(String s) {
		if (s.length() >= 2) {
			char first = s.charAt(0);
			char last = s.charAt(s.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return s.substring(1, s.length() - 1);
			}
		}
		return s;
	}

	private static Object scalar(String raw) {
		String value = raw.trim();
		if (value.isEmpty()) {
			return null;
		}
		if (value.startsWith("[") && value.endsWith("]")) {
			String inner = value.substring(1, value.length() - 1).trim();
			List<Object> list = new ArrayList<>();
			if (!inner.isEmpty()) {
				for (String part : splitFlow(inner)) {
					list.add(scalar(part));
				}
			}
			return list;
		}
		if (value.startsWith("{") && value.endsWith("}")) {
			String inner = value.substring(1, value.length() - 1).trim();
			Map<String, Object> map = new LinkedHashMap<>();
			if (!inner.isEmpty()) {
				for (String part : splitFlow(inner)) {
					int colon = part.indexOf(':');
					if (colon > 0) {
						map.put(unquote(part.substring(0, colon).trim()), scalar(part.substring(colon + 1).trim()));
					}
				}
			}
			return map;
		}
		String unquoted = unquote(value);
		if (!unquoted.equals(value)) {
			return unquoted;
		}
		if (unquoted.equalsIgnoreCase("true")) {
			return Boolean.TRUE;
		}
		if (unquoted.equalsIgnoreCase("false")) {
			return Boolean.FALSE;
		}
		if (unquoted.equalsIgnoreCase("null") || unquoted.equals("~")) {
			return null;
		}
		if (unquoted.matches("[-+]?\\d+")) {
			try {
				return Long.valueOf(unquoted);
			} catch (NumberFormatException e) {
				return unquoted;
			}
		}
		if (unquoted.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) {
			try {
				return Double.valueOf(unquoted);
			} catch (NumberFormatException e) {
				return unquoted;
			}
		}
		return unquoted;
	}

	private static List<String> splitFlow(String inner) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			}
			if (!inSingle && !inDouble) {
				if (c == '[' || c == '{') {
					depth++;
				} else if (c == ']' || c == '}') {
					depth--;
				} else if (c == ',' && depth == 0) {
					out.add(current.toString().trim());
					current.setLength(0);
					continue;
				}
			}
			current.append(c);
		}
		if (!current.toString().isBlank()) {
			out.add(current.toString().trim());
		}
		return out;
	}
}
