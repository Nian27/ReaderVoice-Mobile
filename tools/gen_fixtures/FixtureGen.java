// TASK-010 fixture 生成器（可复现工具，零依赖，java FixtureGen.java 运行）
// 全部内容为自制文本，不含任何私人小说与第三方素材。
// 输出到仓库根 tests/fixtures/txt/
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class FixtureGen {
    static final Path OUT = Paths.get("tests", "fixtures", "txt");

    // 自制中文内容（原创）
    static final String[] PARAS = {
        "　　晨雾未散，林间小径上传来脚步声。",
        "　　他停下脚步，低声说道：“前面好像有人。”",
        "　　阿雪抬起头，目光落向远处的山脊。",
        "　　这一走，便是三年。",
        "　　夜里，烛火摇曳，他把那封信又读了一遍。",
        "　　风从窗口灌进来，桌上的纸页哗哗作响。"
    };

    static String sampleText() {
        StringBuilder sb = new StringBuilder();
        for (String p : PARAS) sb.append(p).append("\n");
        sb.append("最后一行没有句号"); // 常规结尾
        return sb.toString();
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] utf8Bom(String s) {
        byte[] b = utf8(s);
        byte[] out = new byte[b.length + 3];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(b, 0, out, 3, b.length);
        return out;
    }
    static byte[] gb(String s) { return s.getBytes(Charset.forName("GB18030")); }
    static byte[] utf16(String s, boolean le) {
        byte[] b = s.getBytes(le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
        byte[] out = new byte[b.length + 2];
        out[0] = (byte) (le ? 0xFF : 0xFE); out[1] = (byte) (le ? 0xFE : 0xFF);
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    static void write(String name, byte[] data) throws IOException {
        Files.write(OUT.resolve(name), data);
        System.out.println("  " + name + " (" + data.length + " bytes)");
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUT);
        System.out.println("generating fixtures -> " + OUT.toAbsolutePath());

        // 1. UTF-8 LF（无 BOM，标准缩进）
        write("utf8_lf.txt", utf8(sampleText()));
        // 2. UTF-8 BOM + CRLF
        write("utf8_bom_crlf.txt", utf8Bom(sampleText().replace("\n", "\r\n")));
        // 3. GB18030（含 4 字节序列：U+20000 扩展区 → GB18030 特有 4 字节，证明不是 GBK）
        write("gb18030.txt", gb(sampleText() + "\n扩展区字符：𠀀𠀁。")); // 末尾无换行
        // 4/5. UTF-16LE / UTF-16BE（带 BOM）
        write("utf16le.txt", utf16(sampleText(), true));
        write("utf16be.txt", utf16(sampleText(), false));
        // 6. EOF without newline
        write("eof_no_newline.txt", utf8("第一行\n第二行\n最后一行无换行"));
        // 7. mixed newlines: LF + CRLF + CR
        write("mixed_newlines.txt", utf8("line1 LF\nline2 CRLF\r\nline3 CR\rline4 LF\n"));
        // 8. fullwidth indent + tab + trailing spaces（layout 保留验证）
        write("fullwidth_indent.txt", utf8("　　全角缩进两格。\n\tTab 缩进行。\n普通行。  \n　　末尾带半角空格行。 \n"));
        // 9. very long single line（~6000 字）
        StringBuilder longLine = new StringBuilder("超长单行：");
        String unit = "这是一个非常长的句子用来测试超长行的处理逻辑与偏移量计算的正确性。";
        while (longLine.length() < 6000) longLine.append(unit);
        write("very_long_line.txt", utf8(longLine.toString())); // 无换行
        // 10. malformed UTF-8（孤立 continuation + 截断多字节；避免 BOM 前缀干扰）
        write("malformed_utf8.bin", new byte[] {
            0x48, 0x69, (byte) 0x80, (byte) 0xC3, 0x28, 0x0A, (byte) 0xE4, (byte) 0xB8
        });
        // 11. empty
        write("empty.txt", new byte[0]);
        // 12. one line, no newline
        write("one_line.txt", utf8("只有一行"));
        // 13. blank lines
        write("blank_lines.txt", utf8("第一段\n\n\n第四段\n\n"));
        // 14. emoji / supplementary Unicode（surrogate pair）
        write("emoji.txt", utf8("中文😀混合🀄️字符\uD83D\uDE00测试。\n第二行带国旗🇨🇳。\n"));
        // 15. binary / NUL masquerading as txt
        byte[] bin = new byte[200];
        java.util.Arrays.fill(bin, (byte) 0x00);
        bin[0] = 'B'; bin[1] = 'I'; bin[2] = 'N'; bin[3] = 0x01; bin[4] = 0x02;
        write("binary_nul.bin", bin);

        System.out.println("done: " + Files.list(OUT).count() + " files");
    }
}
