package org.icdqcow2.imgtool.bench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.icdqcow2.imgtool.Native;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 功能测试台：把 imgtool 的 38 条命令【扁平化】铺在一页上，每条一个复选框，
 * 勾开就露出参数与「运行」，结果就地显示。界面全部由 Native.list() 的命令表
 * 自动生成 —— 手抄参数一定会抄错，也让命令表本身成为被测试对象。
 *
 * 这是验证用的临时形态，不是最终 UI。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 4101;

    /** 需要以可写方式打开镜像的命令 */
    private static final Set<String> WRITE_CMDS = new HashSet<>(Arrays.asList(
            "img.create", "img.clone", "fs.format", "wim.apply", "fat.put", "ntfs.put",
            "ntfs.rename", "bcd.build", "bcd.create", "bcd.gen-uefi", "boot.repair",
            "part.mbrboot", "part.copy", "part.layout", "part.attr", "recovery.populate",
            "recovery.populate-system", "winre.auto", "iso.extract", "fat.export",
            "ntfs.export", "ctl.cancel",
            /* 2026-10-23 新增的盘内写操作：漏在这里会以【只读】方式打开镜像，
             * 症状是莫名其妙的 FAT-MOUNT-FAIL / PART-FS-BAD，而不是"没权限"。*/
            "fat.rm", "fat.move", "ntfs.rm", "ntfs.move", "win.finish"));

    /** 写目标类参数：给 App 自己目录下的路径（SAF 的 fd 不能当输出用） */
    private static final Set<String> OUT_PARAMS = new HashSet<>(Arrays.asList(
            "out", "dst", "mkdir", "log", "file"));

    /** 读取类文件参数：给「选文件」按钮 */
    private static final Set<String> IN_PARAMS = new HashSet<>(Arrays.asList(
            "img", "iso", "wim", "esd", "src", "ref", "tpl", "winre-src", "bootsdi-src",
            "winre-path"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<CmdCard> cards = new ArrayList<>();

    private ExecutorService worker;
    private File workDir;
    private TextView headView;
    private TextView logView;
    private EditText imgPathEdit;
    private CheckBox forceWrite;
    private CheckBox useSaf;
    private LinearLayout cmdBox;
    private EditText cmdEdit;
    private TextView cmdResult;
    private int safFd = -1;
    private boolean safWritable = true;   // SAF 拿到的 fd 能不能写（有的提供者只授读）
    private String safName = "";
    private Uri lastUri;
    private volatile boolean busy;
    private String lastLine = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        worker = Executors.newSingleThreadExecutor();
        workDir = getExternalFilesDir(null);
        if (workDir == null) workDir = getFilesDir();
        buildShell();
        initNative();
        loadCommands();
        runFromIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        runFromIntent(i);
    }

    /** adb 投喂：am start -n <pkg>/.MainActivity --es cmd "iso.ls --iso=..." */
    private void runFromIntent(Intent i) {
        if (i == null) return;
        String c = i.getStringExtra("cmd");
        if (c == null || c.trim().isEmpty()) return;
        cmdEdit.setText(c.trim());
        runCommandLine();
    }

    /* ---------------- 外壳 ---------------- */

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        headView = text("", 13, true);
        root.addView(headView);

        /* 当前镜像 */
        LinearLayout imgRow = hRow(root);
        imgPathEdit = new EditText(this);
        imgPathEdit.setHint("镜像路径（留空则用 SAF 选的文件）");
        imgPathEdit.setTextSize(12);
        imgPathEdit.setText(new File(workDir, "test.qcow2").getAbsolutePath());
        imgRow.addView(imgPathEdit, weight(1));
        imgRow.addView(btn("选文件", v -> pickFile()));

        LinearLayout optRow = hRow(root);
        useSaf = new CheckBox(this);
        useSaf.setText("用 SAF fd 打开");
        optRow.addView(useSaf);
        forceWrite = new CheckBox(this);
        forceWrite.setText("强制可写");
        optRow.addView(forceWrite);
        optRow.addView(btn("取消当前", v -> { Native.cancel(); appendLog("已请求取消"); }));
        optRow.addView(btn("工作目录…", v -> showWorkDirPicker(null)));
        optRow.addView(btn("把刚选的文件拷进工作目录", v -> { if (lastUri != null) copyToWorkDir(lastUri, safName); else appendLog("先用「选文件」挑一个文件"); }));

        /* 快捷动作 */
        LinearLayout q = hRow(root);
        q.addView(btn("建 2GiB GPT 测试盘", v -> quickCreate()));
        q.addView(btn("格式化 ESP→FAT32", v -> quickFormatEsp()));
        q.addView(btn("展开全部", v -> setAll(true)));
        q.addView(btn("收起全部", v -> setAll(false)));
        q.addView(btn("清空结果", v -> {
            for (CmdCard c : cards) {
                c.result.setText("");
            }
            logView.setText("");
        }));

        /* ---------- 命令行：敲一条就跑，测功能最快的路 ---------- */
        root.addView(section("命令行（直接敲，回车右边「运行」）"));
        LinearLayout cRow = hRow(root);
        cmdEdit = new EditText(this);
        cmdEdit.setTextSize(12);
        cmdEdit.setTypeface(android.graphics.Typeface.MONOSPACE);
        cmdEdit.setSingleLine(false);
        cmdEdit.setHint("例：iso.ls --iso=" + "<工作目录>/xxx.iso");
        cRow.addView(cmdEdit, weight(1));
        cRow.addView(btn("运行", v -> runCommandLine()));
        LinearLayout cHelp = hRow(root);
        cHelp.addView(btn("插入工作目录", v -> cmdEdit.append(workDir.getAbsolutePath())));
        cHelp.addView(btn("插入当前镜像", v -> cmdEdit.append(imgPathEdit.getText().toString().trim())));
        cHelp.addView(btn("上一条命令", v -> { if (!lastLine.isEmpty()) cmdEdit.setText(lastLine); }));
        cmdResult = text("", 11, false);
        cmdResult.setTextIsSelectable(true);
        root.addView(cmdResult);

        cmdBox = new LinearLayout(this);
        cmdBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(cmdBox);

        root.addView(section("事件日志"));
        logView = text("", 11, false);
        logView.setTextIsSelectable(true);
        root.addView(logView);
    }

    private void initNative() {
        StringBuilder sb = new StringBuilder();
        try {
            boolean t = Native.setTmpdir(getCacheDir().getAbsolutePath());
            sb.append("imgtool 版本：").append(Native.version()).append('\n');
            sb.append("临时目录：").append(t ? "已设 " : "设置失败！").append(getCacheDir().getAbsolutePath()).append('\n');
            JSONObject o = new JSONObject(Native.list());
            JSONObject f = o.optJSONObject("features");
            sb.append("编译能力：");
            if (f != null) {
                sb.append("wimlib=").append(f.optBoolean("wimlib"))
                  .append("  ntfs-3g=").append(f.optBoolean("ntfs3g"))
                  .append("  zlib=").append(f.optBoolean("zlib"))
                  .append("  mkntfs=").append(f.optBoolean("mkntfs"));
            }
            sb.append('\n');
            Native.setEventSink(line -> ui.post(() -> appendLog(line)));
        } catch (Throwable e) {
            sb.append("初始化失败：").append(e);
        }
        sb.append("工作目录：").append(workDir.getAbsolutePath())
          .append("\n（ISO/WIM 可以先拷到这里，或点「选文件」用 SAF 打开）");
        headView.setText(sb);
    }

    private void loadCommands() {
        cmdBox.removeAllViews();
        cards.clear();
        try {
            JSONArray cmds = new JSONObject(Native.list()).getJSONArray("cmds");
            for (int i = 0; i < cmds.length(); i++) {
                CmdCard card = new CmdCard(cmds.getJSONObject(i));
                cards.add(card);
                cmdBox.addView(card.root);
            }
            appendLog("命令表已载入：" + cmds.length() + " 条");
        } catch (Exception e) {
            appendLog("载入命令表失败：" + e);
        }
    }

    /* ---------------- 单条命令卡片 ---------------- */

    private class CmdCard {
        final String name;
        final LinearLayout root;
        final CheckBox check;
        final LinearLayout body;
        final TextView result;
        final List<ArgField> args = new ArrayList<>();

        CmdCard(JSONObject c) throws Exception {
            name = c.getString("name");
            String help = c.optString("help", "");
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);

            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(LinearLayout.GONE);

            check = new CheckBox(MainActivity.this);
            check.setText(name + "　—　" + help);
            check.setTextSize(14);
            check.setOnCheckedChangeListener((b, on) -> body.setVisibility(on ? LinearLayout.VISIBLE : LinearLayout.GONE));
            root.addView(check);
            root.addView(body);

            JSONArray as = c.optJSONArray("args");
            if (as != null) {
                for (int i = 0; i < as.length(); i++) {
                    ArgField af = new ArgField(as.getJSONObject(i));
                    args.add(af);
                    body.addView(af.row);
                }
            }

            result = text("", 11, false);
            result.setTextIsSelectable(true);

            LinearLayout run = hRow(body);
            Button b = btn("运行 " + name, v -> runCard());
            run.addView(b);
            run.addView(btn("清空", v -> result.setText("")));
            body.addView(result);
        }

        void runCard() {
            List<String> keys = new ArrayList<>();
            List<String> vals = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (ArgField af : args) {
                String v = af.edit.getText().toString().trim();
                if (!v.isEmpty()) {
                    keys.add(af.argName);
                    vals.add(v);
                } else if (af.required && !af.argName.equals("img")) {
                    missing.add(af.argName);
                }
            }
            if (!missing.isEmpty()) {
                result.setText("缺必填参数：" + String.join("、", missing));
                return;
            }
            boolean writable = forceWrite.isChecked() || WRITE_CMDS.contains(name) || hasOutParam();
            execAsync(name, keys, vals, writable, result);
        }

        boolean hasOutParam() {
            for (ArgField af : args) {
                if (OUT_PARAMS.contains(af.argName) && !af.edit.getText().toString().trim().isEmpty()) return true;
            }
            return false;
        }
    }

    private class ArgField {
        final String argName;
        final boolean required;
        final LinearLayout row;
        final EditText edit;

        ArgField(JSONObject a) throws org.json.JSONException {
            argName = a.getString("name");
            required = a.optBoolean("required", false);
            String type = a.optString("type", "str");
            String def = a.optString("default", "");
            String help = a.optString("help", "");

            row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);

            LinearLayout line = hRow(row);
            TextView label = new TextView(MainActivity.this);
            label.setText(argName + (required ? " *" : "") + "　(" + type + ")");
            label.setTextSize(12);
            line.addView(label, weight(1));

            edit = new EditText(MainActivity.this);
            edit.setTextSize(12);
            edit.setHint(help.isEmpty() ? "（可留空）" : help);
            edit.setText(def);
            if (type.equals("u64")) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
            line.addView(edit, weight(1));

            if (IN_PARAMS.contains(argName)) {
                line.addView(btn("…", v -> pickInto(edit)));
            } else if (OUT_PARAMS.contains(argName)) {
                line.addView(btn("…", v -> edit.setText(new File(workDir, argName + ".out").getAbsolutePath())));
            }
            row.addView(text(help, 11, false));
        }
    }

    /* ---------------- 执行 ---------------- */

    private void execAsync(String cmd, List<String> keys, List<String> vals, boolean writable, TextView out) {
        // 卡片/快捷动作用全局镜像来源（SAF 勾选就走 fd）
        String path = useSaf.isChecked() ? null : imgPathEdit.getText().toString().trim();
        int fd = useSaf.isChecked() ? safFd : -1;
        execAsync(cmd, keys, vals, writable, path, fd, out);
    }

    private void execAsync(String cmd, List<String> keys, List<String> vals, boolean writable,
                           String imagePath, int imageFd, TextView out) {
        if (busy) { out.setText("上一条还在跑（本测试台一次只跑一条）"); return; }
        busy = true;
        final String banner = out.getText().toString();
        out.setText((banner.startsWith("解析") ? banner + "\n" : "") + "运行中… " + cmd);
        final String path = imagePath;
        final int fd = imageFd;
        final String[] ks = keys.toArray(new String[0]);
        final String[] vs = vals.toArray(new String[0]);
        worker.execute(() -> {
            String r;
            long t0 = System.currentTimeMillis();
            try {
                r = Native.exec(cmd, ks, vs, path, fd, writable, false, null, 2);
            } catch (Throwable e) {
                r = "{\"ok\":false,\"err\":\"" + e + "\"}";
            }
            // r 理论上不会为 null，但 CodeQL 按数据流看得到 null 分支 —— 统一兜成空串
            final String res = r == null ? "" : r;
            final long ms = System.currentTimeMillis() - t0;
            ui.post(() -> {
                busy = false;
                boolean ok = res != null && res.contains("\"ok\":true");
                out.setText((banner.startsWith("解析") ? banner + "\n" : "")
                        + (ok ? "✔ " : "✘ ") + ms + " ms\n" + pretty(res));
                appendLog((ok ? "✔ " : "✘ ") + cmd + " → " + (res.length() > 1500 ? res.substring(0, 1500) + "…" : res));
                String code = "";
                java.util.regex.Matcher mm = java.util.regex.Pattern
                        .compile("\"(error_code|code)\":\"([^\"]+)\"").matcher(res);
                if (mm.find()) code = mm.group(2);
                int nfds = 0;
                File[] fdlist = new File("/proc/self/fd").listFiles();
                if (fdlist != null) nfds = fdlist.length;
                long rssKB = -1;                      // native 真实占用（含 wimlib/hivex）
                try {
                    byte[] b = java.nio.file.Files.readAllBytes(new File("/proc/self/statm").toPath());
                    String[] sm = new String(b).trim().split("\\s+");
                    rssKB = Long.parseLong(sm[1]) * 4;   // 页数 × 4 KiB
                } catch (Exception ignore) { }
                long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                appendLog("RESULT cmd=" + cmd + " ok=" + ok + " ms=" + ms
                        + (code.isEmpty() ? "" : " code=" + code)
                        + " fds=" + nfds + " javaHeapMB=" + (mem >> 20) + " rssMB=" + (rssKB / 1024));
            });
        });
    }

    private void quickCreate() {
        imgPathEdit.setText(new File(workDir, "test.qcow2").getAbsolutePath());
        useSaf.setChecked(false);
        execAsync("img.create", list("out", "size-gb", "table", "esp-mb", "force"),
                list(imgPathEdit.getText().toString(), "2", "gpt", "200", "true"),
                true, logAsResult());
    }

    private void quickFormatEsp() {
        execAsync("fs.format", list("img", "lba", "fs", "label"),
                list(imgPathEdit.getText().toString(), "2048", "fat32", "SYSTEM"),
                true, logAsResult());
    }

    private TextView logAsResult() {
        TextView t = new TextView(this);
        t.setTextSize(11);
        return t;   // 快捷动作只看日志面板，不占卡片
    }

    private static List<String> list(String... s) { return new ArrayList<>(Arrays.asList(s)); }

    /** 解析并执行一行命令：`iso.ls --iso=/path/x.iso --index=2` */
    private void runCommandLine() {
        String line = cmdEdit.getText().toString().trim();
        if (line.isEmpty()) { cmdResult.setText("先输一条命令，例如：img.info --img=… 或 iso.ls --iso=…"); return; }
        List<String> tk = tokenize(line);
        if (tk.isEmpty()) { cmdResult.setText("没解析出内容"); return; }
        String cmd = tk.remove(0);

        List<String> keys = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        Boolean forceRw = null;
        String imgArg = null;
        for (int i = 0; i < tk.size(); i++) {
            String t = tk.get(i);
            if (!t.startsWith("--")) { cmdResult.setText("不认识的片段：" + t + "\n只接受 命令名 与 --参数=值"); return; }
            String body = t.substring(2);
            String k, v;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                k = body.substring(0, eq); v = body.substring(eq + 1);
            } else if (i + 1 < tk.size() && !tk.get(i + 1).startsWith("--")) {
                k = body; v = tk.get(++i);
            } else {
                k = body; v = "true";                     // 裸 --flag 当 true
            }
            if (k.equals("writable")) { forceRw = "true".equalsIgnoreCase(v); continue; }   // 保留字，不传给库
            keys.add(k); vals.add(v);
            if (k.equals("img")) imgArg = v;
        }

        boolean autoW = WRITE_CMDS.contains(cmd) || keys.contains("out") || keys.contains("dst");
        boolean writable = (forceRw != null) ? forceRw : (autoW || forceWrite.isChecked());
        String path = (imgArg != null) ? imgArg
                : (useSaf.isChecked() ? null : imgPathEdit.getText().toString().trim());
        int fd = (imgArg == null && useSaf.isChecked()) ? safFd : -1;
        /* SAF 只授读时不许跑写类命令：否则底层会在 write 上拿到 EBADF/EACCES，
         * 报错看起来像"镜像坏了"。这里提前拒并给明确出路。 */
        if (fd >= 0 && !safWritable && writable) {
            cmdResult.setText("拒绝：" + cmd + " 要写盘，但 SAF 给的 fd 只授读（该提供者不许写）\n"
                    + "出路：把文件拷进工作目录（点「把刚选的文件拷进工作目录」），"
                    + "或直接在镜像路径里填 /storage/emulated/0/…");
            appendLog("已拒绝写类命令 " + cmd + "（SAF fd 只读）");
            return;
        }

        lastLine = line;
        cmdResult.setText("解析  " + cmd + "\n  参数 " + keys + " = " + vals
                + "\n  镜像 " + (path != null && !path.isEmpty() ? path : "SAF fd=" + fd)
                + "   可写 " + writable + "\n");
        execAsync(cmd, keys, vals, writable, path, fd, cmdResult);
    }

    /** 按空白切分，支持双引号包住的含空格路径 */
    private static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { inQ = !inQ; continue; }
            if (!inQ && Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /* ---------------- SAF ---------------- */

    /**
     * 选文件。依次尝试：SAF OPEN_DOCUMENT → GET_CONTENT → 都不行就用内置的
     * 工作目录列表。任何一步失败都不允许把 App 崩掉（真机上就崩过一次）。
     */
    private void pickFile() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory("android.intent.category.OPEN_DOCUMENT")
                .setType("*/*");
        if (tryStart(open, "OPEN_DOCUMENT")) return;

        Intent get = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        if (tryStart(get, "GET_CONTENT")) return;

        appendLog("系统没有可用的文件选择器，改用工作目录列表：" + workDir.getAbsolutePath());
        showWorkDirPicker(pendingTarget);
        pendingTarget = null;
    }

    private boolean tryStart(Intent i, String how) {
        try {
            startActivityForResult(i, REQ_PICK);
            appendLog("打开文件选择器（" + how + "）");
            return true;
        } catch (Exception e) {          // ActivityNotFoundException / SecurityException 等
            appendLog(how + " 不可用：" + e.getClass().getSimpleName());
            return false;
        }
    }

    private void pickInto(EditText target) {
        pendingTarget = target;
        pickFile();
    }

    /** 内置选择器：列工作目录里的文件（qcow2/ISO/WIM 拷进来就能直接用真实路径） */
    private void showWorkDirPicker(EditText target) {
        File dir = workDir;
        File[] fs = dir.listFiles();
        final List<File> files = new ArrayList<>();
        if (fs != null) {
            for (File f : fs) if (f.isFile()) files.add(f);
            files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        if (files.isEmpty()) {
            appendLog("工作目录是空的。可以先放文件进去：\n  adb push 你的.iso "
                    + dir.getAbsolutePath() + "/");
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            names[i] = f.getName() + "　" + (f.length() / 1024 / 1024) + " MiB";
        }
        new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(names, (d, which) -> {
                    String path = files.get(which).getAbsolutePath();
                    if (target != null) target.setText(path);
                    else { imgPathEdit.setText(path); useSaf.setChecked(false); }
                    appendLog("已选（工作目录）：" + path);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText pendingTarget;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        lastUri = uri;
        // 只认 content:// + 有 authority 的 URI。外部应用可以通过 ACTION_* 塞进任意 URI
        // （file://、裸路径、伪造 scheme），直接交给 ContentResolver 就是给它一次越权读取的机会。
        if (!isTrustedContentUri(uri)) {
            appendLog("拒绝打开：不是合法的 content:// URI（" + uri + "）");
            return;
        }
        String disp = displayName(uri);
        /* ★ "rw" 先试、失败退 "r"（2026-10-23 真机实测）：
         *   HyperOS 的 com.android.fileexplorer 的 FileExplorerFileProvider 只授【读】授权，
         *   直接 openFileDescriptor(uri,"rw") 会抛
         *   SecurityException: Permission Denial: writing ... requires grantUriPermission()
         *   —— 表现是"选了文件但打不开"，看不出是权限问题。
         *   退到只读之后，只读命令照样能跑；写类命令由 safWritable=false 明确拒绝。*/
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.os.ParcelFileDescriptor p = null;
            try {
                p = cr.openFileDescriptor(uri, "rw");
                safWritable = true;
            } catch (SecurityException se) {
                safWritable = false;
                appendLog("该提供者只授读（不许写）：" + se.getMessage());
                p = cr.openFileDescriptor(uri, "r");
            }
            if (p != null) {
                safFd = p.getFd();          // 故意不关：测试台要让它一直可用
                safName = disp;
            }
        } catch (Exception e) {
            appendLog("打开失败：" + e);
            return;
        }
        if (pendingTarget != null) {
            // 读类参数直接用 /proc/self/fd/N（不拷文件）；要真实路径就用「工作目录…」
            pendingTarget.setText("/proc/self/fd/" + safFd);
            appendLog("已选（SAF）：" + disp + " → /proc/self/fd/" + safFd
                    + "　（只读命令够用；要真实路径请改用「工作目录…」）");
            pendingTarget = null;
        } else {
            imgPathEdit.setText("");
            useSaf.setChecked(true);
            appendLog("当前镜像（SAF fd=" + safFd + "，" + (safWritable ? "可写" : "只读") + "）：" + disp);
        }
        headView.append("\nSAF 已选中：" + disp + "  fd=" + safFd
                + "  " + (safWritable ? "读写" : "只读"));
    }

    private void copyToWorkDir(Uri uri, String name) {
        File dst = new File(workDir, name);
        worker.execute(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.OutputStream os = new java.io.FileOutputStream(dst)) {
                if (in == null) throw new java.io.IOException("打不开");
                byte[] buf = new byte[1 << 20];
                long total = 0; int n;
                while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
                final long t = total;
                ui.post(() -> appendLog("已拷入工作目录：" + dst.getAbsolutePath() + "（" + t + " 字节）"));
            } catch (Exception e) {
                ui.post(() -> appendLog("拷贝失败：" + e));
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignore) { }
        return "picked.bin";
    }

    /* ---------------- 小工具 ---------------- */

    private void setAll(boolean on) {
        for (CmdCard c : cards) c.check.setChecked(on);
    }

    /**
     * URI 白名单校验：必须 content:// 且带非空 authority 与 path。
     * 见 CodeQL java/android/unsafe-content-uri-resolution —— 外部应用塞进来的 URI
     * 不能直接喂给 ContentResolver。
     */
    private static boolean isTrustedContentUri(Uri u) {
        if (u == null) return false;
        if (!"content".equals(u.getScheme())) return false;
        String auth = u.getAuthority();
        return auth != null && !auth.isEmpty() && u.getPath() != null && !u.getPath().isEmpty();
    }

    private void appendLog(String s) {
        if (s == null) return;
        // 日志行里绝不能带换行：s 来自工具端回显/异常信息，含 \n 就能伪造一整条 Log 记录
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        android.util.Log.i("BENCH", flat.length() > 900 ? flat.substring(0, 900) + "…" : flat);
        String cur = logView.getText().toString();
        if (cur.length() > 40000) cur = cur.substring(cur.length() - 20000);
        logView.setText(cur + "\n" + s);
    }

    private static String pretty(String json) {
        try { return new JSONObject(json).toString(2); } catch (Exception e) { return json; }
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private LinearLayout hRow(LinearLayout parent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        parent.addView(l);
        return l;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        return p;
    }

    private Button btn(String label, View.OnClickListener cb) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(cb);
        return b;
    }

    private TextView text(String s, int size, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        if (mono) t.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        t.setLayoutParams(p);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 14, false);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }
}
