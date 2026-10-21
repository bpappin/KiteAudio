/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.attribution

import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

/** Writes `META-INF/LICENSE` and `META-INF/NOTICE` as generated resources, byte for byte from the project root. */
@OptIn(ExperimentalPathApi::class)
@TaskAction
fun copyAttribution(
    @Input license: Path,
    @Input notice: Path,
    @Output outputDir: Path,
) {
    outputDir.deleteRecursively()
    val metaInf = outputDir.resolve("META-INF").createDirectories()
    license.copyTo(metaInf.resolve("LICENSE"), REPLACE_EXISTING)
    notice.copyTo(metaInf.resolve("NOTICE"), REPLACE_EXISTING)
}
