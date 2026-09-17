package com.willykez.md.model

import java.io.File

/**
 * A single markdown document sitting in local storage (not assets).
 * [file] is the actual on-disk file the app reads from / writes to.
 */
data class MdFile(
    val file: File,
    val title: String,
    val badge: String,
    val order: Int
) {
    val name: String get() = file.name
}
