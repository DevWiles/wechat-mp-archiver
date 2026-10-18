package wxarchiver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从任意文本里挑出「属于目标公众号」的完整文章链接。
 * 只认 mp.weixin.qq.com/s?__biz=<目标biz>&mid=..&idx=..&sn=<32位hex>[&chksm=..]，
 * 其余内容一概忽略、不保存。
 */
final class LinkExtractor {

    record Link(String mid, String idx, String sn, String chksm) {
        String key() {
            return mid + "_" + idx;
        }

        String url(String biz) {
            String u = "https://mp.weixin.qq.com/s?__biz=" + biz + "&mid=" + mid + "&idx=" + idx + "&sn=" + sn;
            // 2026-10 起部分公众号缺 chksm 会返回空壳页，所以能带就带上
            return chksm == null ? u : u + "&chksm=" + chksm;
        }
    }

    private static final String AMP = "(?:&|&amp;|\\\\u0026)";
    private final String biz;
    private final Pattern pattern;
    private final Map<String, Link> found = new LinkedHashMap<>();

    LinkExtractor(String biz) {
        this.biz = biz;
        // biz 末尾的 '=' 在内存里可能是原文、%3D 或双重编码的 %253D
        String core = biz.replaceAll("=+$", "");
        this.pattern = Pattern.compile("mp\\.weixin\\.qq\\.com/s\\?__biz=" + Pattern.quote(core)
                + "(?:=|%3D|%253D)*" + AMP + "mid=(\\d+)" + AMP + "idx=(\\d+)" + AMP + "sn=([0-9a-f]{32})"
                + "(?:" + AMP + "chksm=([0-9a-f]{16,80}))?");
    }

    void feed(String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            Link l = new Link(m.group(1), m.group(2), m.group(3), m.group(4));
            Link old = found.get(l.key());
            // 同一篇在内存里可能有多份，优先保留带 chksm、且 chksm 最长的那份
            if (old == null || chksmLen(l) > chksmLen(old)) {
                found.put(l.key(), l);
            }
        }
    }

    private static int chksmLen(Link l) {
        return l.chksm() == null ? 0 : l.chksm().length();
    }

    /** 按 mid 倒序（越新的文章 mid 越大）。 */
    List<String> urls() {
        List<Link> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparingLong((Link l) -> Long.parseLong(l.mid()))
                .thenComparingInt(l -> Integer.parseInt(l.idx())).reversed());
        List<String> out = new ArrayList<>();
        for (Link l : list) {
            out.add(l.url(biz));
        }
        return out;
    }

    int size() {
        return found.size();
    }

    long withChksm() {
        return found.values().stream().filter(l -> l.chksm() != null).count();
    }
}
