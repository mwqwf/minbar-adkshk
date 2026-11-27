package com.ali.menbaradkshk.data

import java.io.IOException

/** A successful status alone does not confirm the server erased this listener's data. */
internal fun requireSupportDeletionReceipt(value: Any?) {
    if (value !is Boolean || !value) throw IOException("Support deletion not confirmed")
}
