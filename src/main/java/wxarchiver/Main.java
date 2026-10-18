package wxarchiver;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用法：
 * <pre>
 *   scan  --biz &lt;biz&gt; --out urls.txt [--proc Weixin.exe,WeChatAppEx.exe]
 *   fetch --biz &lt;biz&gt; --urls urls.txt --out &lt;笔记目录&gt; [--delay 3] [--images local|remote|none] [--limit N]
 * </pre>
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        if (argv.length == 0) {
            usage();
            return;
        }
        Map<String, String> a = parse(Arrays.copyOfRange(argv, 1, argv.length));
        switch (argv[0]) {
            case "scroll" -> scroll(a);
            case "scan" -> scan(a);
            case "fetch" -> fetch(a);
            default -> usage();
        }
    }

    private static void scroll(Map<String, String> a) throws Exception {
        requireWindows("scroll");
        AutoScroller.Result r = AutoScroller.run(procs(a), Integer.parseInt(a.getOrDefault("minutes", "40")));
        if (r == AutoScroller.Result.ABORTED) {
            // 没滚完就扫，只会拿到首屏那二十来篇，不如直接让用户重来
            Archiver.log("滚动未完成，不扫描链接。");
            System.exit(2);
        }
        if (r == AutoScroller.Result.TIME_LIMIT) {
            Archiver.log("注意：因时长上限停止，可能还没到底，扫到的链接可能不全。");
        }
        // 同时给了 --biz 和 --out 时，滚完直接扫链接
        if (a.containsKey("biz") && a.containsKey("out")) {
            Archiver.log("\n开始扫描链接…");
            scan(a);
        }
    }

    private static void scan(Map<String, String> a) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException("scan 只能在 Windows 上运行");
        }
        String biz = require(a, "biz");
        Path outFile = Path.of(require(a, "out"));
        Set<String> procs = new LinkedHashSet<>(List.of(a.getOrDefault("proc", "Weixin.exe,WeChatAppEx.exe").split(",")));

        LinkExtractor ex = new LinkExtractor(biz);
        List<Integer> pids = MemoryScanner.pidsByName(procs);
        if (pids.isEmpty()) {
            Archiver.log("没找到进程 %s。请先打开微信电脑版，进入目标公众号主页并把历史文章滚到底。", procs);
            return;
        }
        for (int pid : pids) {
            int before = ex.size();
            long bytes = MemoryScanner.scan(pid, ex::feed);
            Archiver.log("pid=%d：读取 %s，新增链接 %d", pid,
                    bytes < 0 ? "失败（无权限）" : (bytes >> 20) + "MB", ex.size() - before);
        }
        List<String> urls = ex.urls();
        Files.write(outFile, urls, StandardCharsets.UTF_8);
        Archiver.log("\n目标号文章链接 %d 条（带 chksm 的 %d 条）→ %s", urls.size(), ex.withChksm(), outFile);
        Archiver.log("只保存了链接本身，没有保存任何其它内存内容。");
    }

    private static void fetch(Map<String, String> a) throws Exception {
        String biz = require(a, "biz");
        Path urlsFile = Path.of(require(a, "urls"));
        Path out = Path.of(require(a, "out"));
        double delay = Double.parseDouble(a.getOrDefault("delay", "3"));
        Archiver.Images images = Archiver.Images.valueOf(a.getOrDefault("images", "local").toUpperCase());
        int limit = Integer.parseInt(a.getOrDefault("limit", String.valueOf(Integer.MAX_VALUE)));

        List<String> urls = Files.readAllLines(urlsFile, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(s -> s.startsWith("http")).distinct().toList();
        Archiver.log("待处理 %d 条，间隔约 %.1f 秒，输出到 %s", urls.size(), delay, out);
        new Archiver(out, biz, delay, images).run(urls, limit);
    }

    private static Set<String> procs(Map<String, String> a) {
        return new LinkedHashSet<>(List.of(a.getOrDefault("proc", "Weixin.exe,WeChatAppEx.exe").split(",")));
    }

    private static void requireWindows(String cmd) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new IllegalStateException(cmd + " 只能在 Windows 上运行");
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("参数格式应为 --key value：" + args[i]);
            }
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private static String require(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + k);
        }
        return v;
    }

    private static void usage() {
        Archiver.log("""
                wx-archiver —— 公众号历史文章 → Markdown（Obsidian）

                1) 在微信电脑版里打开目标公众号主页，鼠标停在文章列表上，自动慢速滚到底并扫链接：
                   java -jar wx-archiver.jar scroll --biz <biz> --out urls.txt [--minutes 40]
                   （也可以自己手动滚到底，再单独运行 scan --biz <biz> --out urls.txt）

                2) 抓正文、图片落本地、生成索引（可中断，重跑自动续抓）：
                   java -jar wx-archiver.jar fetch --biz <biz> --urls urls.txt --out <笔记目录> \\
                        [--delay 3] [--images local|remote|none] [--limit 20]
                """);
    }
}
