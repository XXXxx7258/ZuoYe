import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 JSON 解析器，仅支持对象、数组、字符串、数字、布尔和 null。
 */
public final class MiniJson {
    private final String text;
    private int index;

    private MiniJson(String text) {
        this.text = text;
        this.index = 0;
    }

    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        return new MiniJson(json).parseValue();
    }

    private Object parseValue() {
        skipWhitespace();
        if (index >= text.length()) {
            return null;
        }
        char c = text.charAt(index);
        if (c == '{') {
            return parseObject();
        } else if (c == '[') {
            return parseArray();
        } else if (c == '"') {
            return parseString();
        } else if (c == 't' || c == 'f') {
            return parseBoolean();
        } else if (c == 'n') {
            return parseNull();
        } else {
            return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new HashMap<>();
        index++; // skip '{'
        skipWhitespace();
        if (peek('}')) {
            index++;
            return map;
        }
        while (index < text.length()) {
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (peek('}')) {
                index++;
                break;
            }
            expect(',');
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        index++; // skip '['
        skipWhitespace();
        if (peek(']')) {
            index++;
            return list;
        }
        while (index < text.length()) {
            Object value = parseValue();
            list.add(value);
            skipWhitespace();
            if (peek(']')) {
                index++;
                break;
            }
            expect(',');
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && index < text.length()) {
                char esc = text.charAt(index++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (index + 4 <= text.length()) {
                            String hex = text.substring(index, index + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ignored) {
                            }
                            index += 4;
                        }
                        break;
                    default: sb.append(esc); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        return Boolean.FALSE;
    }

    private Object parseNull() {
        if (text.startsWith("null", index)) {
            index += 4;
        }
        return null;
    }

    private Number parseNumber() {
        int start = index;
        while (index < text.length()) {
            char c = text.charAt(index);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                index++;
            } else {
                break;
            }
        }
        String num = text.substring(start, index);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void expect(char c) {
        skipWhitespace();
        if (index < text.length() && text.charAt(index) == c) {
            index++;
        }
    }

    private boolean peek(char c) {
        skipWhitespace();
        return index < text.length() && text.charAt(index) == c;
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                index++;
            } else {
                break;
            }
        }
    }
}
