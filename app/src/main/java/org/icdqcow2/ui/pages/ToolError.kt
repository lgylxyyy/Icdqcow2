package org.icdqcow2.ui.pages

import org.icdqcow2.core.ToolRunner
import org.icdqcow2.ui.Strings

/**
 * 把工具端失败信封翻成人话。
 *
 * 工具端从 2026-10-25 起，失败时也可能带 `data`
 * （`PART-SIZE-BAD` → `min/max/role`，`PART-NOSPACE` → `avail_mb`），
 * 所以这里优先用 `data` 里的真实范围，没有才退回 `DiskLimits`。
 */
internal fun toolErrorText(s: Strings, r: ToolRunner.Result): String {
    val data = r.data
    return when (r.code) {
        "PART-SIZE-BAD" -> {
            val min = data?.optInt("min", 0) ?: 0
            val max = data?.optInt("max", 0) ?: 0
            // max=0 = 工具端的"无上限" → 只说下限（不然会显示成"16384–∞ MB"）
            if (max <= 0) s.errorSizeMin(fmtMb(min)) else s.errorSize(min, max)
        }
        "PART-NOSPACE" -> {
            val avail = data?.optInt("avail_mb", -1) ?: -1
            val needGb = data?.optInt("need_gb", 0) ?: 0
            when {
                // `part.apply` 只告诉我们"至少要多大"，没有 avail_mb
                avail < 0 && needGb > 0 -> s.errorNeedSpace("$needGb GB")
                else -> s.errorNoSpace(if (avail >= 0) fmtMb(avail) else "?")
            }
        }
        "CMD-ARG-BAD" -> when (r.at) {
            // 卷标不合法（FAT32 中文 / 超长）：说清是哪条规则，别只报"参数不对"
            "label" -> s.errLabelBad
            else -> s.errorArg(r.at ?: "?")
        }
        "PART-READ-FAIL" -> s.errorNoTable
        // 常见错误码全部翻成人话；实在没有的才回退成"未知错误（码）"
        "IMG-OPEN-FAIL" -> s.errOpen
        "QC-OPEN-FAIL" -> s.errQcOpen
        "IMG-FORMAT" -> s.errImgFormat
        "HOST-OPEN-FAIL" -> s.errHostOpen
        "HOST-ENOSPC" -> s.errHostNoSpace
        "PART-WRITE-FAIL" -> s.errPartWrite
        "PART-JSON-FAIL" -> s.errPartJson
        "PART-FS-BAD" -> s.errFsBad
        "NTFS-FORMAT-FAIL" -> s.errNtfsFormat
        "FAT-PUT-FAIL" -> s.errFatPut
        "FAT-MOUNT-FAIL" -> s.errFatMount
        "FAT-LS-FAIL" -> s.errFatLs
        "FAT-ENTRY-NOTFOUND" -> s.errEntryNotFound
        "SRC-NOTFOUND" -> s.errSrcNotFound
        // 文件页 copy/move：源路径不存在（多半是符号链接，比如 /sdcard）
        "PATH-SRC-NOTFOUND" -> s.errSrcNotFound
        // 打包时 --src 给的是只读容器内的路径（a.zip!:/x）→ 不支持，先导出到本地
        "ARCHIVE-SRC-CONTAINER" -> s.errArchiveSrcContainer
        // 统一路径 / copy / move / rm / 压缩包这一套（REPLY-21.11.13 起，2026-10-28 补齐）
        "PATH-BAD" -> s.errPathBad
        "PATH-NOT-FOUND" -> s.errPathNotFound
        "PATH-DST-NOTFOUND" -> s.errPathDstNotFound
        "PATH-EXISTS-FILE" -> s.errPathExistsFile
        "PATH-FS-BAD" -> s.errPathFsBad
        "PATH-PART-AMBIGUOUS" -> s.errPathPartAmbiguous
        "PATH-READONLY" -> s.errPathReadonly
        "COPY-DST-EXISTS" -> s.errCopyDstExists
        "COPY-DST-CONFLICT" -> s.errCopyDstConflict
        "COPY-UNSUPPORTED" -> s.errCopyUnsupported
        "COPY-SRC-UNSUPPORTED" -> s.errCopySrcUnsupported
        "COPY-INTO-SELF" -> s.errCopyIntoSelf
        "COPY-NO-SPACE" -> s.errCopyNoSpace
        "COPY-SAME-FILE" -> s.errCopySameFile
        "COPY-VERIFY-FAIL" -> s.errCopyVerifyFail
        "COPY-NAME-TOO-LONG" -> s.errCopyNameTooLong
        "COPY-FILE-TOO-BIG" -> s.errCopyFileTooBig
        "MOVE-PARTIAL" -> s.errMovePartial
        "MOVE-HOST-DELETE-REFUSED" -> s.errMoveHostDeleteRefused
        "MOVE-REFUSED" -> s.errMoveRefused
        "RM-DIR-NOT-EMPTY" -> s.errRmDirNotEmpty
        "RM-ROOT-REFUSED" -> s.errRmRootRefused
        "RM-HOST-DELETE-REFUSED" -> s.errRmHostDeleteRefused
        "RM-REFUSED" -> s.errRmRefused
        "NAME-INVALID" -> s.errNameInvalid
        "ARCHIVE-NOTFOUND" -> s.errArchiveNotFound
        // solid RAR：libarchive 只有读 solid 的 rar4，rar5 solid 读不了 —— 说清楚，别只报"打不开"
        "ARCHIVE-OPEN-FAIL" ->
            if ((r.hint ?: "").contains("solid", true)) s.errArchiveSolid else s.errArchiveOpenFail
        "ARCHIVE-READ-FAIL" -> s.errArchiveReadFail
        "ARCHIVE-ENCRYPTED" -> s.errArchiveEncrypted
        "ARCHIVE-EXISTS" -> s.errArchiveExists
        "ARCHIVE-FORMAT-READONLY" -> s.errArchiveFormatReadonly
        "ARCHIVE-ENTRY-NOTFOUND" -> s.errArchiveEntryNotFound
        "ARCHIVE-CREATE-FAIL" -> s.errArchiveCreateFail
        "ARCHIVE-WRITE-FAIL" -> s.errArchiveWriteFail
        "ARCHIVE-NAME-DUP" -> s.errArchiveNameDup
        "INDEX-EXPIRED" -> s.errIndexExpired
        "LIST-TOO-BIG" -> s.errListTooBig
        "DU-FAIL" -> s.errDuFail
        "DU-UNSUPPORTED" -> s.errDuUnsupported
        "CTL-CANCELLED" -> s.errCtlCancelled
        "CLONE-COPY-FAIL" -> s.errCloneCopy
        "CLONE-VERIFY-FAIL" -> s.errCloneVerify
        // 卷已经建好了、只是名字没写上（工具端标了 formatted=true）→ 别吓人
        "LABEL-WRITE-FAIL" -> if (r.formatted) s.errLabelSkipped else s.errLabelWrite
        "CMD-CANCELLED" -> s.errCancelled
        "CMD-NOTFOUND" -> s.errNotFound
        "FEATURE-UNAVAILABLE" -> s.errFeature
        "LIST-FAIL" -> s.errListFail
        "JNI-FAIL" -> s.errJni
        // 表里没有的码：中文界面兜底用工具端的 hint（它只有中文），英文界面仍报码。
        // 但 hint 前面常挂着"已完成 0 个文件 / 0.0 MiB 后"这种进度前缀——
        // 一个文件都没成功时显示"已完成 0 个文件 / 0.0 MiB"纯属添乱，先切掉。
        else -> {
            val hint = r.hint
                ?.replace(Regex("^已完成[^后]*后"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            hint?.takeIf { s.code == "zh" } ?: s.errUnknown(r.code ?: r.at ?: "?")
        }
    }
}