package com.layerbit.abhyas.data.occlusion

/**
 * The only file names the image store produces: a random id and `.jpg`.
 *
 * Pure, so the backup codec can check names from an untrusted file without Android. A name like
 * `../databases/abhyas.db` written back verbatim would overwrite the collection itself.
 */
object ImageNames {
    private val SAFE = Regex("""[A-Za-z0-9-]{8,64}\.jpg""")

    fun isSafe(name: String): Boolean = SAFE.matches(name)
}
