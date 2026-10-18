package wxarchiver;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 把公众号正文 DOM 转成 Markdown（只覆盖公众号正文里实际会出现的标签）。 */
final class Markdown {

    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "section", "article", "header", "footer", "figure", "figcaption",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li", "pre", "hr", "table", "tr");

    // 公众号正文里常见的模板文本
    private static final List<Pattern> JUNK = List.of(
            Pattern.compile("(?m)^\\s*点击上方.*?关注.*?$"),
            Pattern.compile("(?m)^\\s*点击.*?蓝字.*?$"),
            Pattern.compile("(?m)^\\s*长按识别二维码.*?$"),
            Pattern.compile("(?m)^\\s*扫描(上方|下方)?二维码.*?$"),
            Pattern.compile("(?m)^\\s*微信扫一扫.*?$"),
            Pattern.compile("(?m)^\\s*预览时标签不可点\\s*$"),
            Pattern.compile("(?m)^\\s*轻点两下取消(赞|在看)\\s*$"),
            Pattern.compile("(?m)^\\s*继续滑动看下一个\\s*$"),
            Pattern.compile("(?m)^\\s*喜欢此内容的人还喜欢\\s*$"),
            Pattern.compile("(?m)^\\s*(分享|收藏|点赞|在看|赞|写留言|阅读原文|点击阅读原文)\\s*$"));

    private final StringBuilder out = new StringBuilder();

    private Markdown() {
    }

    static String convert(Element root) {
        Markdown md = new Markdown();
        md.children(root);
        return clean(md.out.toString());
    }

    static String clean(String s) {
        for (Pattern p : JUNK) {
            s = p.matcher(s).replaceAll("");
        }
        s = s.replace((char) 0xA0, ' ')   // &nbsp;
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return s.strip() + "\n";
    }

    private void children(Node n) {
        for (Node c : n.childNodes()) {
            node(c);
        }
    }

    private void node(Node n) {
        if (n instanceof TextNode t) {
            String text = t.getWholeText().replaceAll("[\\r\\n\\t]+", " ");
            if (!text.isBlank() || (out.length() > 0 && out.charAt(out.length() - 1) != '\n')) {
                out.append(text.replaceAll(" {2,}", " "));
            }
            return;
        }
        if (!(n instanceof Element e)) {
            return;
        }
        String tag = e.normalName();
        switch (tag) {
            case "script", "style", "noscript", "svg", "iframe", "mpvoice", "mpvideo" -> { }
            case "br" -> out.append("  \n");
            case "hr" -> block("---");
            case "img" -> {
                String src = e.hasAttr("data-src") ? e.attr("data-src") : e.attr("src");
                if (!src.isBlank() && !src.startsWith("data:")) {
                    out.append("![](").append(src.trim()).append(")");
                }
            }
            case "strong", "b" -> wrap(e, "**");
            case "em", "i" -> wrap(e, "*");
            case "code" -> {
                if (e.parent() != null && "pre".equals(e.parent().normalName())) {
                    children(e);
                } else {
                    out.append('`').append(e.text()).append('`');
                }
            }
            case "a" -> {
                String href = e.attr("href");
                String text = e.text().strip();
                if (href.startsWith("http") && !text.isEmpty()) {
                    out.append('[').append(text).append("](").append(href).append(')');
                } else {
                    children(e);
                }
            }
            case "pre" -> block("```\n" + e.wholeText().strip() + "\n```");
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = e.text().strip();
                if (!text.isEmpty()) {
                    block("#".repeat(tag.charAt(1) - '0') + " " + text);
                }
            }
            case "blockquote" -> {
                String inner = convert(e).strip();
                if (!inner.isEmpty()) {
                    block("> " + inner.replace("\n", "\n> "));
                }
            }
            case "li" -> {
                Element p = e.parent();
                String bullet = p != null && "ol".equals(p.normalName()) ? (e.elementSiblingIndex() + 1) + ". " : "- ";
                newline();
                out.append(bullet).append(convert(e).strip().replace("\n", "\n  ")).append('\n');
            }
            case "td", "th" -> {
                children(e);   // 公众号里的表格多用于排版，单元格之间用空格分隔
                out.append(' ');
            }
            default -> {
                if (BLOCKS.contains(tag)) {
                    paragraphBreak();
                    children(e);
                    paragraphBreak();
                } else {
                    children(e); // span / font / 其它行内标签
                }
            }
        }
    }

    private void wrap(Element e, String mark) {
        String text = e.text().strip();
        if (text.isEmpty()) {
            children(e);   // 可能只包着图片
        } else {
            out.append(mark).append(text).append(mark);
        }
    }

    private void block(String s) {
        paragraphBreak();
        out.append(s);
        paragraphBreak();
    }

    private void newline() {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private void paragraphBreak() {
        if (out.length() > 0) {
            out.append("\n\n");
        }
    }
}
