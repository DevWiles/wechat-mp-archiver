package wxarchiver;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 只读扫描指定进程的内存，把每一块内存以文本形式交给回调（回调只做正则匹配，不落盘）。
 * <p>
 * 权限只申请 PROCESS_VM_READ | PROCESS_QUERY_INFORMATION：
 * 不写内存、不注入、不创建线程。只在 Windows 上可用。
 */
final class MemoryScanner {

    private static final int CHUNK = 8 * 1024 * 1024;
    private static final int OVERLAP = 8 * 1024;   // 块之间重叠，防止链接被切断
    private static final long MAX_ADDR = 0x7FFF_FFFF_FFFFL;
    private static final Set<Integer> READABLE = Set.of(
            WinNT.PAGE_READONLY, WinNT.PAGE_READWRITE, 0x20 /* EXECUTE_READ */, 0x40 /* EXECUTE_READWRITE */);
    // UTF-16LE 形式的 "mp.weixin"，出现时才额外按 UTF-16 解码一遍
    private static final byte[] UTF16_MARK = "mp.weixin".getBytes(StandardCharsets.UTF_16LE);

    private MemoryScanner() {
    }

    static List<Integer> pidsByName(Set<String> exeNames) {
        Kernel32 k32 = Kernel32.INSTANCE;
        List<Integer> out = new ArrayList<>();
        WinNT.HANDLE snap = k32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        try {
            Tlhelp32.PROCESSENTRY32 e = new Tlhelp32.PROCESSENTRY32();
            for (boolean ok = k32.Process32First(snap, e); ok; ok = k32.Process32Next(snap, e)) {
                String exe = Native.toString(e.szExeFile);
                if (exeNames.stream().anyMatch(exe::equalsIgnoreCase)) {
                    out.add(e.th32ProcessID.intValue());
                }
            }
        } finally {
            k32.CloseHandle(snap);
        }
        return out;
    }

    /** @return 实际读取的字节数；进程打不开时返回 -1 */
    static long scan(int pid, Consumer<String> sink) {
        Kernel32 k32 = Kernel32.INSTANCE;
        WinNT.HANDLE h = k32.OpenProcess(WinNT.PROCESS_VM_READ | WinNT.PROCESS_QUERY_INFORMATION, false, pid);
        if (h == null) {
            return -1;
        }
        long total = 0;
        Memory buf = new Memory(CHUNK + OVERLAP);
        byte[] bytes = new byte[CHUNK + OVERLAP];
        try {
            long addr = 0;
            WinNT.MEMORY_BASIC_INFORMATION mbi = new WinNT.MEMORY_BASIC_INFORMATION();
            BaseTSD.SIZE_T mbiSize = new BaseTSD.SIZE_T(mbi.size());
            while (addr < MAX_ADDR) {
                if (k32.VirtualQueryEx(h, new Pointer(addr), mbi, mbiSize).longValue() == 0) {
                    break;
                }
                long base = Pointer.nativeValue(mbi.baseAddress);
                long size = mbi.regionSize.longValue();
                int protect = mbi.protect.intValue();
                boolean readable = mbi.state.intValue() == WinNT.MEM_COMMIT
                        && READABLE.contains(protect & 0xFF) && (protect & WinNT.PAGE_GUARD) == 0;
                if (readable && size > 0) {
                    for (long off = 0; off < size; off += CHUNK) {
                        int want = (int) Math.min(CHUNK + OVERLAP, size - off);
                        IntByReference got = new IntByReference();
                        if (k32.ReadProcessMemory(h, new Pointer(base + off), buf, want, got) && got.getValue() > 0) {
                            int n = got.getValue();
                            total += n;
                            buf.read(0, bytes, 0, n);
                            emit(bytes, n, sink);
                        }
                    }
                }
                long next = base + size;
                if (next <= addr) {
                    break;
                }
                addr = next;
            }
        } finally {
            k32.CloseHandle(h);
        }
        return total;
    }

    private static void emit(byte[] bytes, int n, Consumer<String> sink) {
        // ISO-8859-1 是逐字节一一映射，ASCII 形式的链接原样保留
        sink.accept(new String(bytes, 0, n, StandardCharsets.ISO_8859_1));
        if (indexOf(bytes, n, UTF16_MARK) >= 0) {
            sink.accept(new String(bytes, 0, n & ~1, StandardCharsets.UTF_16LE));
            if (n > 1) {
                sink.accept(new String(bytes, 1, (n - 1) & ~1, StandardCharsets.UTF_16LE));
            }
        }
    }

    private static int indexOf(byte[] hay, int n, byte[] needle) {
        outer:
        for (int i = 0; i <= n - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
