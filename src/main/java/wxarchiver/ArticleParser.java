package wxarchiver;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析公众号文章页：元数据 + 正文 Markdown + 图片列表。 */
final class ArticleParser {

    enum Status { OK, BLOCKED, INVALID, GONE }

    record Article(Status status, String biz, String mid, String idx, String sn, String title, String account,
                   String author, String date, String markdown, List<String> images) {
        String key() {
            return mid + "_" + idx;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final Pattern IMG_MD = Pattern.compile("!\\[[^\\]]*]\\(([^)\\s]+)\\)");
    private static final Pattern CONTENT_JS = Pattern.compile("content_noencode:\\s*'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern HTML_TAG = Pattern.compile(
            "<\\s*(p|section|div|img|br|h[1-6]|ul|ol|li|span|strong)\\b", Pattern.CASE_INSENSITIVE);

    private ArticleParser() {
    }

    static Article parse(String html) {
        String biz = firstVar(html, "var biz = \"([^\"]+)\"");
        String mid = firstVar(html, "var mid = \"(\\d+)\"");
        String idx = firstVar(html, "var idx = \"(\\d+)\"");
        String sn = firstVar(html, "var sn = \"([0-9a-f]+)\"");
        String title = firstVar(html, "var msg_title = '(.*?)'\\.html");
        boolean hasBody = html.contains("id=\"js_content\"") || html.contains("content_noencode")
                || html.contains("picture_page_info_list");

        if (!hasBody || mid.isEmpty()) {
            Status s = html.contains("环境异常") || html.contains("wappoc_appmsgcaptcha") ? Status.BLOCKED
                    : html.contains("参数错误") ? Status.INVALID : Status.GONE;
            return new Article(s, biz, mid, idx, sn, "", "", "", "", "", List.of());
        }

        Document doc = Jsoup.parse(html);
        if (title.isEmpty()) {
            Element og = doc.selectFirst("meta[property=og:title]");
            title = og != null ? og.attr("content") : doc.title();
        }
        title = Parser.unescapeEntities(jsUnescape(title), false).strip();
        // 不用 og:site_name，它恒为「微信公众平台」
        String account = Parser.unescapeEntities(firstVar(html, "var nickname = htmlDecode\\(\"([^\"]+)\"\\)"), false);
        Element authorEl = doc.selectFirst("meta[name=author]");
        String author = authorEl != null ? authorEl.attr("content").strip() : "";
        String ct = firstVar(html, "var ct = \"(\\d{9,11})\"");
        String date = ct.isEmpty() ? firstVar(html, "create_time:\\s*'(\\d{4}-\\d{2}-\\d{2})")
                : DAY.format(Instant.ofEpochSecond(Long.parseLong(ct)));

        String md = "";
        Element content = doc.selectFirst("#js_content");
        if (content != null) {
            md = Markdown.convert(content);
        }
        // 活动/优惠券类文章：没有 #js_content，也没有 var msg_title，正文只在 JS 字符串里。
        // 不能比长度——壳页里的模板文本可能比真正文还长。
        if (content == null || !html.contains("var msg_title") || md.strip().length() < 40) {
            String js = bodyFromJs(html);
            if (!js.isBlank()) {
                md = js;
            }
        }
        // 图集型文章（图片消息）：主体是 picture_page_info_list
        List<String> pics = picturePages(html);
        if (!pics.isEmpty()) {
            StringBuilder sb = new StringBuilder(md.strip());
            for (String u : pics) {
                if (!md.contains("](" + u + ")")) {
                    sb.append("\n\n![](").append(u).append(")");
                }
            }
            md = sb.append('\n').toString();
        }

        // 图片 URL 一律从生成的 Markdown 里反查，保证替换时字符串完全一致
        Set<String> images = new LinkedHashSet<>();
        Matcher m = IMG_MD.matcher(md);
        while (m.find()) {
            images.add(m.group(1));
        }
        return new Article(Status.OK, biz, mid, idx, sn, title, account, author, date, md, new ArrayList<>(images));
    }

    private static String bodyFromJs(String html) {
        Matcher m = CONTENT_JS.matcher(html);
        if (!m.find()) {
            return "";
        }
        String text = jsUnescape(m.group(1));
        if (HTML_TAG.matcher(text).find()) {
            return Markdown.convert(Jsoup.parseBodyFragment(text).body());
        }
        return Markdown.clean(text);
    }

    /** 还原 JS 字符串里的 \xHH / \\uXXXX / \n 等转义（只解 \n 的话正文会是满屏 \x3cp\x3e）。 */
    static String jsUnescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> { }
                case 'x' -> {
                    if (isHex(s, i + 1, 2)) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    } else {
                        sb.append(n);
                    }
                }
                case 'u' -> {
                    if (isHex(s, i + 1, 4)) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        sb.append(n);
                    }
                }
                default -> sb.append(n); // \' \" \\ \/
            }
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int from, int len) {
        if (from + len > s.length()) {
            return false;
        }
        for (int i = from; i < from + len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 每个 page 对象里有 3 个 cdn_url（主图 / 水印 / 分享封面），只取第一个。 */
    private static List<String> picturePages(String html) {
        Matcher m = Pattern.compile("picture_page_info_list\\s*:\\s*\\[").matcher(html);
        if (!m.find()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        int i = m.end();
        int depth = 1;
        int objStart = -1;
        char quote = 0;
        int limit = Math.min(html.length(), i + 500_000);
        for (; i < limit && depth > 0; i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> quote = c;
                case '[' -> depth++;
                case ']' -> depth--;
                case '{' -> {
                    if (depth == 1 && objStart < 0) {
                        objStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 1 && objStart >= 0) {
                        String obj = html.substring(objStart, i + 1);
                        objStart = -1;
                        if (obj.matches("(?s).*is_qr_code\\s*:\\s*'1'.*")) {
                            continue;
                        }
                        Matcher u = Pattern.compile("cdn_url\\s*:\\s*'([^']+)'").matcher(obj);
                        if (u.find()) {
                            // JS 字符串里的 \x26amp;amp; 要先解 JS 转义，再解两层 HTML 实体
                            String url = jsUnescape(u.group(1));
                            for (int k = 0; k < 2; k++) {
                                url = Parser.unescapeEntities(url, true);
                            }
                            if (!urls.contains(url)) {
                                urls.add(url);
                            }
                        }
                    }
                }
                default -> { }
            }
        }
        return urls;
    }

    private static String firstVar(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : "";
    }
}
