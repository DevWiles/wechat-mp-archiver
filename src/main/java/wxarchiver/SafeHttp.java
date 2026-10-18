package wxarchiver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 全项目唯一的出网口。
 * <ul>
 *   <li>只发 GET，没有任何上传 / POST；</li>
 *   <li>目标域名写死白名单：文章页只能是 mp.weixin.qq.com，图片只能是微信图床；</li>
 *   <li>不自动跟随重定向，避免被跳转到白名单之外。</li>
 * </ul>
 */
final class SafeHttp {

    private static final Set<String> PAGE_HOSTS = Set.of("mp.weixin.qq.com");
    private static final Set<String> IMAGE_HOSTS = Set.of(
            "mmbiz.qpic.cn", "mmbiz.qlogo.cn", "mmecoa.qpic.cn", "wx.qlogo.cn", "res.wx.qq.com");

    // 用 PC 微信内置浏览器的 UA：普通浏览器 UA 连续请求会拿到「环境异常」壳页
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 "
            + "MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
            + "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a1b)";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    String getPage(String url) throws IOException, InterruptedException {
        URI uri = checked(url, PAGE_HOSTS);
        HttpResponse<byte[]> r = client.send(request(uri), HttpResponse.BodyHandlers.ofByteArray());
        // 手动跟随重定向（如加 nwr_flag=1），每一跳都重新过白名单，最多 3 跳
        for (int hop = 0; hop < 3 && r.statusCode() / 100 == 3; hop++) {
            String loc = r.headers().firstValue("Location").orElseThrow(() -> new IOException("重定向缺少 Location"));
            uri = checked(uri.resolve(loc.replaceAll("#.*$", "")).toString(), PAGE_HOSTS);
            r = client.send(request(uri), HttpResponse.BodyHandlers.ofByteArray());
        }
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode());
        }
        // 文章页恒为 UTF-8，不去猜编码（猜编码可能误判成 GBK，标题变乱码）
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /** 返回图片字节；域名不在白名单时抛 IllegalArgumentException。 */
    HttpResponse<byte[]> getImage(String url) throws IOException, InterruptedException {
        URI uri = checked(url, IMAGE_HOSTS);
        return client.send(request(uri), HttpResponse.BodyHandlers.ofByteArray());
    }

    static boolean isAllowedImage(String url) {
        try {
            checked(url, IMAGE_HOSTS);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Referer", "https://mp.weixin.qq.com/") // 微信图片防盗链
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
    }

    private static URI checked(String url, Set<String> hosts) {
        String u = url.startsWith("//") ? "https:" + url : url;
        if (u.startsWith("http://")) {
            u = "https://" + u.substring("http://".length());
        }
        URI uri;
        try {
            uri = URI.create(u.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 URL: " + url);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !hosts.contains(uri.getHost().toLowerCase())) {
            throw new IllegalArgumentException("域名不在白名单，拒绝访问: " + uri.getHost());
        }
        return uri;
    }
}
