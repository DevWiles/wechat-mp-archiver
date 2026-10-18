package wxarchiver;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟手动滚轮，把公众号主页的历史文章滚到底：每一下拨半圈（9–13 格），停一停再拨。
 * <ul>
 *   <li>只发系统级滚轮事件（java.awt.Robot），不点击、不输入、不调用微信界面接口；</li>
 *   <li>鼠标下面的窗口属于微信进程、并且鼠标静止 3 秒后才开始；</li>
 *   <li>滚动中鼠标被移动、或鼠标下面不再是微信窗口，立即停止；</li>
 *   <li>鼠标附近画面连续多格不变即判定到底。</li>
 * </ul>
 */
final class AutoScroller {

    enum Result { BOTTOM, TIME_LIMIT, ABORTED }

    private static final int STILL_FLICKS_TO_STOP = 6;    // 连续多少下画面不变算到底
    private static final int MOUSE_TOLERANCE = 6;         // 物理像素，超过即视为用户接管
    private static final long HOLD_STILL_MS = 3000;       // 开始前鼠标需静止多久
    private static final long WAIT_START_MS = 120_000;    // 最多等用户就位多久

    /** JNA 自带的 User32 没有 WindowFromPoint，单独声明。 */
    private interface U32 extends StdCallLibrary {
        U32 INSTANCE = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);

        WinDef.HWND WindowFromPoint(WinDef.POINT.ByValue point);
    }

    private AutoScroller() {
    }

    static Result run(Set<String> procs, int maxMinutes) throws Exception {
        Set<Integer> wechatPids = new HashSet<>(MemoryScanner.pidsByName(procs));
        if (wechatPids.isEmpty()) {
            Archiver.log("没找到微信进程 %s，请先打开微信电脑版。", procs);
            return Result.ABORTED;
        }
        Archiver.log("""
                请切到微信里的公众号主页，把鼠标放在文章列表上，然后别动鼠标。
                鼠标在微信窗口上静止 3 秒后自动开始（最多等 2 分钟）；开始后动一下鼠标就会停止。""");
        WinDef.POINT anchor = waitUntilReady(wechatPids);
        if (anchor == null) {
            Archiver.log("2 分钟内没有检测到鼠标停在微信窗口上，已取消。");
            return Result.ABORTED;
        }
        Archiver.log("开始滚动…");

        Robot robot = new Robot();
        Rectangle probe = probeArea(MouseInfo.getPointerInfo().getLocation());
        int[] last = snapshot(robot, probe);
        int still = 0;
        int flicks = 0;
        long deadline = System.currentTimeMillis() + maxMinutes * 60_000L;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int nextBreak = rnd.nextInt(6, 13);

        while (System.currentTimeMillis() < deadline) {
            // 一下拨半圈（9–13 格）
            String stop = flick(robot, rnd.nextInt(9, 14), anchor, wechatPids, rnd);
            if (stop != null) {
                Archiver.log("%s，已停止（共拨动 %d 下）。", stop, flicks);
                return Result.ABORTED;
            }
            flicks++;
            Thread.sleep(rnd.nextLong(600, 1500));   // 等新文章加载

            // 偶尔往回拨一点，像回看了一眼刚划过的标题
            if (rnd.nextInt(15) == 0) {
                int back = rnd.nextInt(2, 5);
                if ((stop = flick(robot, -back, anchor, wechatPids, rnd)) == null) {
                    Thread.sleep(rnd.nextLong(800, 2000));
                    stop = flick(robot, back, anchor, wechatPids, rnd);
                }
                if (stop != null) {
                    Archiver.log("%s，已停止（共拨动 %d 下）。", stop, flicks);
                    return Result.ABORTED;
                }
            }
            // 每拨 6–12 下歇一会儿
            if (--nextBreak == 0) {
                Thread.sleep(rnd.nextLong(3000, 8000));
                nextBreak = rnd.nextInt(6, 13);
            }

            int[] shot = snapshot(robot, probe);
            if (Arrays.equals(shot, last)) {
                still++;
                Thread.sleep(2000);   // 可能只是还在加载，多等一会儿再判断
            } else {
                still = 0;
            }
            last = shot;
            if (still >= STILL_FLICKS_TO_STOP) {
                Archiver.log("画面已连续 %d 下没有变化，判定到底（共拨动 %d 下）。", still, flicks);
                Archiver.log("请看一眼页面：如果出现「操作频繁」之类提示，说明被限流了，过段时间再继续。");
                return Result.BOTTOM;
            }
            if (flicks % 10 == 0) {
                Archiver.log("  已拨动 %d 下…", flicks);
            }
        }
        Archiver.log("达到时长上限 %d 分钟，已停止（共拨动 %d 下）。", maxMinutes, flicks);
        return Result.TIME_LIMIT;
    }

    /**
     * 像手指拨一下滚轮：连续发 |notches| 格，开头结尾慢、中间快。
     * 每格之前都检查一次鼠标，保证用户一动就能停。
     *
     * @return null 表示正常拨完；否则返回停止原因
     */
    private static String flick(Robot robot, int notches, WinDef.POINT anchor, Set<Integer> pids,
                                ThreadLocalRandom rnd) throws InterruptedException {
        int n = Math.abs(notches);
        int dir = Integer.signum(notches);
        for (int i = 0; i < n; i++) {
            WinDef.POINT p = cursor();
            if (distance(p, anchor) > MOUSE_TOLERANCE) {
                return "检测到鼠标移动";
            }
            if (!wechatUnder(p, pids)) {
                return "鼠标下面已不是微信窗口";
            }
            robot.mouseWheel(dir);
            boolean edge = i < 2 || i >= n - 2;
            Thread.sleep(edge ? rnd.nextLong(30, 60) : rnd.nextLong(12, 30));
        }
        return null;
    }

    /** 等到鼠标停在微信窗口上并静止 3 秒；超时返回 null。 */
    private static WinDef.POINT waitUntilReady(Set<Integer> pids) throws InterruptedException {
        long giveUp = System.currentTimeMillis() + WAIT_START_MS;
        WinDef.POINT spot = null;
        long since = 0;
        boolean announced = false;
        while (System.currentTimeMillis() < giveUp) {
            WinDef.POINT p = cursor();
            if (!wechatUnder(p, pids)) {
                spot = null;
                announced = false;
            } else if (spot == null || distance(p, spot) > MOUSE_TOLERANCE) {
                spot = p;
                since = System.currentTimeMillis();
                if (!announced) {
                    Archiver.log("  检测到鼠标在微信窗口上，保持别动…");
                    announced = true;
                }
            } else if (System.currentTimeMillis() - since >= HOLD_STILL_MS) {
                return spot;
            }
            Thread.sleep(200);
        }
        return null;
    }

    /** 物理像素坐标（与 WindowFromPoint 一致，不受系统缩放影响）。 */
    private static WinDef.POINT cursor() {
        WinDef.POINT p = new WinDef.POINT();
        User32.INSTANCE.GetCursorPos(p);
        return p;
    }

    private static double distance(WinDef.POINT a, WinDef.POINT b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    /** 鼠标下面的顶层窗口是否属于微信进程（滚轮默认作用于鼠标下面的窗口）。 */
    private static boolean wechatUnder(WinDef.POINT p, Set<Integer> pids) {
        WinDef.HWND hwnd = U32.INSTANCE.WindowFromPoint(new WinDef.POINT.ByValue(p.x, p.y));
        if (hwnd == null) {
            return false;
        }
        WinDef.HWND root = User32.INSTANCE.GetAncestor(hwnd, WinUser.GA_ROOT);
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(root != null ? root : hwnd, pid);
        return pids.contains(pid.getValue());
    }

    /** 以鼠标为中心取一块区域做「是否还在变化」的探针（Java 逻辑坐标）。 */
    private static Rectangle probeArea(Point p) {
        var screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = 360;
        int h = 480;
        int x = Math.max(0, Math.min(p.x - w / 2, screen.width - w));
        int y = Math.max(0, Math.min(p.y - h / 2, screen.height - h));
        return new Rectangle(x, y, w, h);
    }

    /** 降采样后的像素，足以判断画面是否在变。 */
    private static int[] snapshot(Robot robot, Rectangle r) {
        BufferedImage img = robot.createScreenCapture(r);
        int step = 8;
        int[] px = new int[(r.width / step) * (r.height / step)];
        int i = 0;
        for (int y = 0; y + step <= r.height; y += step) {
            for (int x = 0; x + step <= r.width; x += step) {
                px[i++] = img.getRGB(x, y);
            }
        }
        return px;
    }
}
