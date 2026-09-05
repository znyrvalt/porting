package com.altarsmp.fabric.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Faithful renderer for the text markup used by the original AltarSMP plugin.
 *
 * <p>The original implementation formatted every weapon name, lore line, chat
 * announcement and hologram with Adventure's MiniMessage plus legacy Bukkit
 * {@code ChatColor} codes.  Fabric has no MiniMessage, so the exact subset that
 * the AltarSMP sources actually use is implemented here and converted into real
 * {@link Component} trees:</p>
 *
 * <ul>
 *   <li>{@code <gradient:#RRGGBB:#RRGGBB[:...]>text</gradient>} - per-character
 *       linear interpolation across the whole character run (identical to how
 *       MiniMessage builds gradients).</li>
 *   <li>{@code <rainbow>[phase] [saturation] [lightness]>text</rainbow>}</li>
 *   <li>Named colour tags: {@code <red> <gold> <dark_gray> <gray> <yellow>
 *       <white> <aqua> <dark_aqua> <blue> <dark_blue> <dark_red> <dark_purple>
 *       <light_purple> <green> <dark_green> <black>}</li>
 *   <li>{@code <color:#RRGGBB>} / {@code <color:name>}</li>
 *   <li>Decoration tags: {@code <bold> <b> <italic> <i> <em> <underlined> <u>
 *       <strikethrough> <st> <obfuscated> <o>}</li>
 *   <li>Legacy section sign / ampersand codes ({@code \u00a76}, {@code &6})
 *       including {@code \u00a7l} style codes and {@code \u00a7r} reset.</li>
 * </ul>
 *
 * <p>Unclosed tags are tolerated exactly like MiniMessage does (the original lore
 * contains many of them, e.g. {@code "<red>Kills: <white>0"}).</p>
 */
public final class TextFx {

	private static final Map<String, ChatFormatting> COLORS = new HashMap<>();

	static {
		COLORS.put("black", ChatFormatting.BLACK);
		COLORS.put("dark_blue", ChatFormatting.DARK_BLUE);
		COLORS.put("dark_green", ChatFormatting.DARK_GREEN);
		COLORS.put("dark_aqua", ChatFormatting.DARK_AQUA);
		COLORS.put("dark_red", ChatFormatting.DARK_RED);
		COLORS.put("dark_purple", ChatFormatting.DARK_PURPLE);
		COLORS.put("gold", ChatFormatting.GOLD);
		COLORS.put("gray", ChatFormatting.GRAY);
		COLORS.put("grey", ChatFormatting.GRAY);
		COLORS.put("dark_gray", ChatFormatting.DARK_GRAY);
		COLORS.put("dark_grey", ChatFormatting.DARK_GRAY);
		COLORS.put("blue", ChatFormatting.BLUE);
		COLORS.put("green", ChatFormatting.GREEN);
		COLORS.put("aqua", ChatFormatting.AQUA);
		COLORS.put("red", ChatFormatting.RED);
		COLORS.put("light_purple", ChatFormatting.LIGHT_PURPLE);
		COLORS.put("yellow", ChatFormatting.YELLOW);
		COLORS.put("white", ChatFormatting.WHITE);
	}

	private TextFx() {
	}

	/** Parses markup into a component. Null/empty input yields {@link Component#empty()}. */
	public static Component parse(String input) {
		if (input == null || input.isEmpty()) {
			return Component.empty();
		}
		Node root = new Node(null, null);
		parse(input, root);
		MutableComponent out = Component.empty();
		render(root, Style.EMPTY, null, out);
		return out;
	}

	/** Convenience: a literal component with no markup interpretation. */
	public static Component plain(String text) {
		return text == null || text.isEmpty() ? Component.empty() : Component.literal(text);
	}

	/** Strips every tag/legacy code - used for plain-text logs and file names. */
	public static String strip(String input) {
		if (input == null) {
			return "";
		}
		String noTags = input.replaceAll("</?[a-zA-Z#_0-9:]+[^>]*>", "");
		return noTags.replaceAll("[\u00a7&][0-9a-fk-orA-FK-OR]", "");
	}

	// ------------------------------------------------------------------ parsing

	private static final class Node {
		final String tag;
		final String arg;
		final List<Node> children = new ArrayList<>();
		String text;

		Node(String tag, String arg) {
			this.tag = tag;
			this.arg = arg;
		}

		boolean isText() {
			return this.tag == null;
		}
	}

	private static void parse(String input, Node root) {
		List<Node> stack = new ArrayList<>();
		stack.add(root);
		StringBuilder text = new StringBuilder();
		int i = 0;
		int n = input.length();
		while (i < n) {
			char c = input.charAt(i);
			if (c == '<') {
				int close = input.indexOf('>', i);
				if (close > i) {
					String body = input.substring(i + 1, close);
					if (isTagBody(body)) {
						flush(stack, text);
						i = close + 1;
						applyTag(stack, body);
						continue;
					}
				}
				text.append(c);
				i++;
			} else if ((c == '\u00a7' || c == '&') && i + 1 < n && isLegacyCode(input.charAt(i + 1))) {
				flush(stack, text);
				Node node = new Node("#legacy", String.valueOf(Character.toLowerCase(input.charAt(i + 1))));
				stack.get(stack.size() - 1).children.add(node);
				stack.add(node);
				i += 2;
			} else {
				text.append(c);
				i++;
			}
		}
		flush(stack, text);
	}

	private static boolean isLegacyCode(char c) {
		char l = Character.toLowerCase(c);
		return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || l == 'k' || l == 'l' || l == 'm' || l == 'n' || l == 'o' || l == 'r' || l == 'x';
	}

	private static boolean isTagBody(String body) {
		if (body.isEmpty()) {
			return false;
		}
		String name = body.startsWith("/") ? body.substring(1) : body;
		int sp = indexOfDelimiter(name);
		if (sp >= 0) {
			name = name.substring(0, sp);
		}
		if (name.isEmpty()) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == ':' || c == '-' || c == '.';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	private static int indexOfDelimiter(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == ' ' || c == ':' || c == '=') {
				return i;
			}
		}
		return -1;
	}

	private static void flush(List<Node> stack, StringBuilder text) {
		if (text.length() > 0) {
			Node node = new Node(null, null);
			node.text = text.toString();
			stack.get(stack.size() - 1).children.add(node);
			text.setLength(0);
		}
	}

	private static void applyTag(List<Node> stack, String body) {
		boolean closing = body.startsWith("/");
		String name = closing ? body.substring(1) : body;
		String arg = null;
		int sp = indexOfDelimiter(name);
		if (sp >= 0) {
			arg = name.substring(sp + 1).trim();
			name = name.substring(0, sp);
		}
		name = name.toLowerCase(Locale.ROOT);
		if (closing) {
			for (int i = stack.size() - 1; i >= 1; i--) {
				if (name.equals(stack.get(i).tag)) {
					while (stack.size() > i) {
						stack.remove(stack.size() - 1);
					}
					return;
				}
			}
			return;
		}
		if ("br".equals(name)) {
			Node nl = new Node(null, null);
			nl.text = "\n";
			stack.get(stack.size() - 1).children.add(nl);
			return;
		}
		Node node = new Node(name, arg);
		stack.get(stack.size() - 1).children.add(node);
		if (isSelfContained(name)) {
			return;
		}
		stack.add(node);
	}

	private static boolean isSelfContained(String name) {
		return "reset".equals(name);
	}

	// ----------------------------------------------------------------- rendering

	private static void render(Node node, Style style, Colorizer colorizer, MutableComponent out) {
		if (node.isText()) {
			emit(node.text, style, colorizer, out);
			return;
		}
		Style child = style;
		Colorizer cz = colorizer;
		switch (node.tag) {
			case "#legacy" -> child = applyLegacy(style, node.arg);
			case "gradient" -> cz = gradient(node.arg, textLength(node));
			case "rainbow" -> cz = rainbow(node.arg, textLength(node));
			case "color", "colour" -> child = style.withColor(TextColor.fromRgb(parseColorArg(node.arg, 0xFFFFFF)));
			case "bold", "b" -> child = style.withBold(true);
			case "italic", "i", "em" -> child = style.withItalic(true);
			case "underlined", "u" -> child = style.withUnderlined(true);
			case "strikethrough", "st" -> child = style.withStrikethrough(true);
			case "obfuscated", "o" -> child = style.withObfuscated(true);
			case "reset" -> {
				child = Style.EMPTY;
				cz = null;
			}
			default -> {
				ChatFormatting fmt = COLORS.get(node.tag);
				if (fmt != null) {
					child = style.withColor(fmt);
				}
				Boolean deco = decoration(node.tag);
				if (deco != null) {
					child = switch (node.tag) {
						case "bold", "b" -> child.withBold(deco);
						case "italic", "i", "em" -> child.withItalic(deco);
						case "underlined", "u" -> child.withUnderlined(deco);
						case "strikethrough", "st" -> child.withStrikethrough(deco);
						default -> child.withObfuscated(deco);
					};
				}
			}
		}
		for (Node kid : node.children) {
			render(kid, child, cz, out);
		}
		if (node.children.isEmpty()) {
			// A tag with no children still has to emit nothing, but its style must
			// be able to apply to following siblings in MiniMessage semantics.
			emit("", child, cz, out);
		}
	}

	private static Boolean decoration(String tag) {
		return switch (tag) {
			case "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "o" -> Boolean.TRUE;
			default -> null;
		};
	}

	private static Style applyLegacy(Style style, String code) {
		return switch (code) {
			case "0" -> style.withColor(ChatFormatting.BLACK);
			case "1" -> style.withColor(ChatFormatting.DARK_BLUE);
			case "2" -> style.withColor(ChatFormatting.DARK_GREEN);
			case "3" -> style.withColor(ChatFormatting.DARK_AQUA);
			case "4" -> style.withColor(ChatFormatting.DARK_RED);
			case "5" -> style.withColor(ChatFormatting.DARK_PURPLE);
			case "6" -> style.withColor(ChatFormatting.GOLD);
			case "7" -> style.withColor(ChatFormatting.GRAY);
			case "8" -> style.withColor(ChatFormatting.DARK_GRAY);
			case "9" -> style.withColor(ChatFormatting.BLUE);
			case "a" -> style.withColor(ChatFormatting.GREEN);
			case "b" -> style.withColor(ChatFormatting.AQUA);
			case "c" -> style.withColor(ChatFormatting.RED);
			case "d" -> style.withColor(ChatFormatting.LIGHT_PURPLE);
			case "e" -> style.withColor(ChatFormatting.YELLOW);
			case "f" -> style.withColor(ChatFormatting.WHITE);
			case "k", "o" -> style.withObfuscated(true);
			case "l" -> style.withBold(true);
			case "m" -> style.withStrikethrough(true);
			case "n" -> style.withUnderlined(true);
			case "r" -> Style.EMPTY;
			default -> style;
		};
	}

	private static void emit(String text, Style style, Colorizer colorizer, MutableComponent out) {
		if (text.isEmpty()) {
			return;
		}
		if (colorizer == null) {
			out.append(Component.literal(text).withStyle(style));
			return;
		}
		// Per-character colouring: build one child per character, exactly like
		// MiniMessage does for <gradient>/<rainbow>.
		int index = colorizer.start;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			int rgb = colorizer.colorAt(index);
			if (ch == ' ') {
				out.append(Component.literal(" ").withStyle(style));
			} else {
				out.append(Component.literal(String.valueOf(ch)).withStyle(style.withColor(TextColor.fromRgb(rgb))));
			}
			index++;
		}
		colorizer.start = index;
	}

	private interface ColorSource {
		int colorAt(int index);
	}

	private static final class Colorizer implements ColorSource {
		private final ColorSource source;
		private final int length;
		int start;

		Colorizer(ColorSource source, int length) {
			this.source = source;
			this.length = Math.max(1, length);
		}

		@Override
		public int colorAt(int index) {
			return this.source.colorAt(Math.min(index, this.length - 1));
		}
	}

	/** Total number of characters inside a node's subtree (gradients span the run). */
	private static int textLength(Node node) {
		if (node.isText()) {
			return node.text == null ? 0 : node.text.length();
		}
		int total = 0;
		for (Node kid : node.children) {
			total += textLength(kid);
		}
		return total;
	}

	private static Colorizer gradient(String arg, int length) {
		List<Integer> stops = parseColorList(arg);
		int len = Math.max(1, length);
		if (stops.isEmpty()) {
			return new Colorizer(i -> 0xFFFFFF, len);
		}
		if (stops.size() == 1) {
			int only = stops.get(0);
			return new Colorizer(i -> only, len);
		}
		// MiniMessage interpolates across the whole tagged run.
		return new Colorizer(new GradientSource(stops, len), len);
	}

	private static Colorizer rainbow(String arg, int length) {
		float phase = 0f;
		float saturation = 1f;
		float lightness = 1f;
		if (arg != null && !arg.isBlank()) {
			String[] parts = arg.trim().split("\\s+");
			try {
				if (parts.length > 0) {
					phase = Float.parseFloat(parts[0]);
				}
				if (parts.length > 1) {
					saturation = Float.parseFloat(parts[1]);
				}
				if (parts.length > 2) {
					lightness = Float.parseFloat(parts[2]);
				}
			} catch (NumberFormatException ignored) {
				// keep defaults, same as MiniMessage on malformed input
			}
		}
		float p = phase;
		float s = saturation;
		float l = lightness;
		int len = Math.max(1, length);
		return new Colorizer(i -> hslToRgb((p + i * 1.0f / 12.0f) % 1.0f, s, l), len);
	}

	private static final class GradientSource implements ColorSource {
		private final List<Integer> stops;
		private final int length;

		GradientSource(List<Integer> stops, int length) {
			this.stops = stops;
			this.length = Math.max(2, length);
		}

		@Override
		public int colorAt(int index) {
			if (this.stops.size() == 1) {
				return this.stops.get(0);
			}
			int segments = this.stops.size() - 1;
			float t = Math.min(1.0f, index / (float) (this.length - 1));
			float scaled = t * segments;
			int seg = Math.min(segments - 1, (int) Math.floor(scaled));
			float local = scaled - seg;
			return mix(this.stops.get(seg), this.stops.get(seg + 1), local);
		}
	}

	private static int mix(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(bl);
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	private static int hslToRgb(float h, float s, float l) {
		float c = (1 - Math.abs(2 * l - 1)) * s;
		float hp = (h * 6f) % 6f;
		float x = c * (1 - Math.abs(hp % 2 - 1));
		float r = 0, g = 0, b = 0;
		if (hp < 1) { r = c; g = x; }
		else if (hp < 2) { r = x; g = c; }
		else if (hp < 3) { g = c; b = x; }
		else if (hp < 4) { g = x; b = c; }
		else if (hp < 5) { r = x; b = c; }
		else { r = c; b = x; }
		float m = l - c / 2;
		return (clamp255(Math.round((r + m) * 255)) << 16) | (clamp255(Math.round((g + m) * 255)) << 8) | clamp255(Math.round((b + m) * 255));
	}

	private static List<Integer> parseColorList(String arg) {
		List<Integer> out = new ArrayList<>();
		if (arg == null) {
			return out;
		}
		for (String part : arg.split("[:\\s]+")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			out.add(parseColorArg(p, 0xFFFFFF));
		}
		return out;
	}

	private static int parseColorArg(String raw, int fallback) {
		if (raw == null) {
			return fallback;
		}
		String v = raw.trim();
		if (v.startsWith("#")) {
			v = v.substring(1);
		}
		if (v.matches("[0-9a-fA-F]{6}")) {
			return Integer.parseInt(v, 16);
		}
		if (v.matches("[0-9a-fA-F]{3}")) {
			StringBuilder sb = new StringBuilder();
			for (char c : v.toCharArray()) {
				sb.append(c).append(c);
			}
			return Integer.parseInt(sb.toString(), 16);
		}
		ChatFormatting fmt = COLORS.get(v.toLowerCase(Locale.ROOT));
		if (fmt != null && fmt.getColor() != null) {
			return fmt.getColor();
		}
		try {
			return Integer.parseInt(v) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
