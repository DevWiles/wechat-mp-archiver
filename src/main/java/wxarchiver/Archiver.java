package wxarchiver;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 按 URL 清单逐篇抓取 → Markdown 笔记 + 本地图片 + 索引。
 * 只新建 / 覆盖自己生成的文件，没有任何删除操作。
 */
final class Archiver {

    enum Images { LOCAL, REMOTE, NONE }

    private static final String STATE = ".archiver-state.tsv";
    private static final String INDEX = "00-索引.md";
    private static final Pattern KEY_IN_URL = Pattern.compile("mid=(\\d+).*?idx=(\\d+)");
    private static final Pattern FM_FIELD = Pattern.compile("(?m)^(title|date):\\s*\"?(.*?)\"?\\s*$");

    private final SafeHttp http = new SafeHttp();
    private final Path out;
    private final Path attachments;
    private final String biz;
    private final double delaySec;
    private final Images images;
    private final Set<String> done = new HashSet<>();

    Archiver(Path out, String biz, double delaySec, Images images) {
        this.out = out;
        this.attachments = out.resolve("attachments");
        this.biz = biz;
        this.delaySec = delaySec;
        this.images = images;
    }

    void run(List<String> urls, int limit) throws IOException, InterruptedException {
        Files.createDirectories(attachments);
        loadState();
        int ok = 0;
        int skipped = 0;
        int blockedInARow = 0;
        for (int i = 0; i < urls.size() && ok < limit; i++) {
            String url = urls.get(i);
            String key = keyOf(url);
            if (done.contains(url) || (key != null && done.contains(key))) {
                skipped++;
                continue;
            }
            ArticleParser.Article a;
            try {
                a = ArticleParser.parse(http.getPage(url));
            } catch (IOException e) {
                log("  [网络错误] %s %s", url, e.getMessage());
                pause();
                continue;
            }
            if (a.status() == ArticleParser.Status.BLOCKED) {
                blockedInARow++;
                if (blockedInARow >= 3) {
                    log("\n连续 %d 次被风控（环境异常 / 验证码），停止。过几小时再跑，会从断点继续。", blockedInARow);
                    break;
                }
                long wait = 60L * blockedInARow;
                log("  [风控] 等 %d 秒后重试", wait);
                Thread.sleep(wait * 1000);
                i--;
                continue;
            }
            blockedInARow = 0;
            if (a.status() != ArticleParser.Status.OK) {
                log("  [%s] %s", a.status() == ArticleParser.Status.GONE ? "已删除/不可访问" : "链接无效", url);
                record(key != null ? key : url, a.status().name(), "");
                pause();
                continue;
            }
            if (!biz.equals(a.biz())) {
                log("  [非目标号，跳过] %s（%s）", a.title(), a.account());
                record(a.key(), "OTHER", "");
                pause();
                continue;
            }
            Path note = writeNote(a, url);
            record(a.key(), "OK", note.getFileName().toString());
            if (key == null) {
                // /s/xxx 短链里看不出 mid_idx，把链接本身也记下，否则重跑会再抓一遍
                record(url, "OK", note.getFileName().toString());
            }
            ok++;
            log("  [%d] %s %s", ok, a.date(), a.title());
            pause();
        }
        Path index = writeIndex();
        log("\n本次新增 %d 篇，跳过已完成 %d 条。索引：%s", ok, skipped, index);
    }

    // ------------------------------------------------------------------ 笔记

    private Path writeNote(ArticleParser.Article a, String sourceUrl) throws IOException, InterruptedException {
        String body = a.markdown();
        for (String img : a.images()) {
            body = switch (images) {
                case NONE -> body.replace("![](" + img + ")", "");
                case REMOTE -> body;
                case LOCAL -> {
                    String local = downloadImage(img);
                    yield local == null ? body : body.replace("](" + img + ")", "](attachments/" + local + ")");
                }
            };
        }
        String base = sanitize((a.date().isEmpty() ? "" : a.date() + " ") + a.title());
        Path note = out.resolve(base + ".md");
        if (Files.exists(note) && !Files.readString(note).contains("\nmid_idx: " + a.key() + "\n")) {
            note = out.resolve(base + " (" + a.key() + ").md");   // 同名不同文，保留两篇
        }
        String canonical = "https://mp.weixin.qq.com/s?__biz=" + a.biz() + "&mid=" + a.mid() + "&idx=" + a.idx()
                + "&sn=" + a.sn();
        String fm = "---\n"
                + "title: \"" + a.title().replace("\"", "'") + "\"\n"
                + "account: \"" + a.account().replace("\"", "'") + "\"\n"
                + (a.author().isEmpty() ? "" : "author: \"" + a.author().replace("\"", "'") + "\"\n")
                + (a.date().isEmpty() ? "" : "date: " + a.date() + "\n")
                + "source: " + (sourceUrl.contains("chksm=") ? sourceUrl : canonical) + "\n"
                + "mid_idx: " + a.key() + "\n"
                + "type: 公众号文章\n"
                + "tags: [公众号/" + a.account().replaceAll("\\s+", "") + "]\n"
                + "imported: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n"
                + "---\n\n";
        Files.writeString(note, fm + "# " + a.title() + "\n\n" + body.strip() + "\n", StandardCharsets.UTF_8);
        return note;
    }

    /** @return attachments 下的文件名；不在白名单或下载失败返回 null（正文保留原外链） */
    private String downloadImage(String url) throws InterruptedException {
        if (!SafeHttp.isAllowedImage(url)) {
            return null;
        }
        String name = md5(url).substring(0, 12) + ext(url);
        Path dest = attachments.resolve(name);
        try {
            if (Files.exists(dest) && Files.size(dest) > 0) {
                return name;
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpResponse<byte[]> r = http.getImage(url);
                if (r.statusCode() == 200 && r.body().length > 0) {
                    Files.write(dest, r.body());
                    return name;
                }
                Thread.sleep(500L * (attempt + 1));
            }
        } catch (IOException e) {
            log("    图片下载失败（保留外链）：%s", e.getMessage());
        }
        return null;
    }

    private static String ext(String url) {
        Matcher m = Pattern.compile("wx_fmt=(\\w+)").matcher(url);
        if (m.find()) {
            String f = m.group(1).toLowerCase();
            return switch (f) {
                case "png", "gif", "webp", "bmp" -> "." + f;
                case "svg" -> ".svg";
                default -> ".jpg";
            };
        }
        return ".jpg";
    }

    // ------------------------------------------------------------------ 索引 / 状态

    private Path writeIndex() throws IOException {
        record Entry(String date, String stem, String title) { }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(out)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals(INDEX))
                    .toList()) {
                String head = Files.readString(p);
                String date = "";
                String title = "";
                Matcher m = FM_FIELD.matcher(head.substring(0, Math.min(head.length(), 1500)));
                while (m.find()) {
                    if (m.group(1).equals("date")) {
                        date = m.group(2);
                    } else {
                        title = m.group(2);
                    }
                }
                String stem = p.getFileName().toString().replaceAll("\\.md$", "");
                entries.add(new Entry(date, stem, title));
            }
        }
        entries.sort((x, y) -> y.date().compareTo(x.date()));
        StringBuilder sb = new StringBuilder("---\ntype: 索引\nupdated: "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\n---\n\n"
                + "# 文章索引\n\n共 " + entries.size() + " 篇。\n\n");
        for (Entry e : entries) {
            sb.append("- ").append(e.date().isEmpty() ? "" : e.date() + " ").append("[[").append(e.stem());
            if (!e.title().isEmpty() && !e.stem().endsWith(sanitize(e.title()))) {
                sb.append('|').append(e.title());
            }
            sb.append("]]\n");
        }
        Path index = out.resolve(INDEX);
        Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);
        return index;
    }

    /** 清单里还没处理过的条数（判定规则与 run() 跳过已完成的规则一致）。 */
    static long pending(Path out, List<String> urls) throws IOException {
        Archiver probe = new Archiver(out, "", 0, Images.NONE);
        probe.loadState();
        return urls.stream().filter(u -> {
            String k = keyOf(u);
            return !probe.done.contains(u) && (k == null || !probe.done.contains(k));
        }).count();
    }

    private void loadState() throws IOException {
        Path p = out.resolve(STATE);
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length >= 2) {
                    done.add(f[0]);
                }
            }
        }
    }

    private void record(String key, String status, String file) throws IOException {
        done.add(key);
        Files.writeString(out.resolve(STATE), key + "\t" + status + "\t" + file + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ 工具

    private void pause() throws InterruptedException {
        // 随机抖动，避免固定节奏
        double s = delaySec * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.6);
        Thread.sleep((long) (s * 1000));
    }

    private static String keyOf(String url) {
        Matcher m = KEY_IN_URL.matcher(url);
        return m.find() ? m.group(1) + "_" + m.group(2) : null;
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t#^\\[\\]]", " ").replaceAll("\\s+", " ").strip();
        s = s.replaceAll("^\\.+|\\.+$", "");
        return s.length() > 90 ? s.substring(0, 90).strip() : (s.isEmpty() ? "untitled" : s);
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void log(String fmt, Object... args) {
        System.out.println(args.length == 0 ? fmt : String.format(fmt, args));
    }
}
