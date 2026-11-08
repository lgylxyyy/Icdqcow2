import org.icdqcow2.imgtool.Native;

/**
 * 主机 JNI 冒烟测试（不是 Android UI 测试）。
 * 用法：java -Djava.library.path=<dir> -cp <classes> TestMain <qcow2> [espLba srcFile]
 */
public final class TestMain {
    static int pass = 0, fail = 0;
    static void ok(String m){ System.out.println("  ✅ " + m); pass++; }
    static void ng(String m){ System.out.println("  ❌ " + m); fail++; }

    public static void main(String[] args){
        if(args.length < 1){
            System.err.println("用法: TestMain <qcow2> [espLba srcFile]");
            System.exit(2);
        }
        String img = args[0];
        System.out.println("════ JNI 冒烟测试 ════");
        ok("version = " + Native.version());

        String list = Native.list();
        ok("list 可解析且含 boot.repair = " + (list != null && list.contains("\"boot.repair\"")));

        String r = Native.exec("img.info", new String[]{"img"}, new String[]{img},
                               null, -1, false, false, null, 2);
        ok("img.info ok = " + (r != null && r.contains("\"ok\":true") && r.contains("\"table\":\"GPT\"")));

        String p = Native.exec("part.show", new String[]{"img"}, new String[]{img},
                               null, -1, false, false, null, 2);
        ok("part.show 含 parts = " + (p != null && p.contains("\"parts\"")));

        String bad = Native.exec("nosuch.cmd", null, null, null, -1, false, false, null, 2);
        ok("未知命令 → CMD-NOTFOUND = " + (bad != null && bad.contains("CMD-NOTFOUND")));
        String le = Native.lastError();
        ok("lastError 有 CMD-NOTFOUND = " + (le != null && le.contains("CMD-NOTFOUND")));

        // 事件回调：只有真正干活的命令才有 begin/done
        final int[] events = {0};
        final int[] badEv = {0};
        // ★ 事件行必须是合法 JSON：桥里曾漏写键间逗号（{"level":"INFO""logger":...}），
        //   只数条数发现不了。这里查两个最容易断的分隔点。
        Native.setEventSink(json -> {
            events[0]++;
            if (json == null || !json.startsWith("{") || !json.endsWith("}")
                    || !json.contains("\",\"logger\":")
                    || json.contains("\"INFO\"\"")) badEv[0]++;
        });
        if(args.length >= 3){
            String put = Native.exec("fat.put",
                    new String[]{"img", "lba", "src", "dst"},
                    new String[]{img, args[1], args[2], "/EFI/jni-test.bin"},
                    null, -1, true, false, null, 2);
            ok("fat.put ok = " + (put != null && put.contains("\"ok\":true")));
            ok("收到事件 " + events[0] + " 条 = " + (events[0] > 0));
            ok("事件行是合法 JSON（坏行 " + badEv[0] + "） = " + (badEv[0] == 0));
        } else {
            System.out.println("  ⏭ 未给 espLba/src，跳过 fat.put 事件验证");
        }
        Native.setEventSink(null);

        // 取消标志必须在下一个命令开始时被清掉
        Native.cancel();
        String after = Native.exec("img.info", new String[]{"img"}, new String[]{img},
                                   null, -1, false, false, null, 2);
        ok("取消后下一次命令自动复位 = " + (after != null && after.contains("\"ok\":true")));

        System.out.println("════ 结果：通过 " + pass + " · 失败 " + fail + " ════");
        System.exit(fail == 0 ? 0 : 1);
    }
}