// 🎁 حزمة الأصول «core»: أكثر الدروس استماعاً (≤480 م.ب) — fast-follow: تصل بعد التثبيت تلقائياً.
// محتواها يُملأ بـ tools/assetpack/fetch.py (في CI) — لا يُودَع في git.
plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("core")
    dynamicDelivery { deliveryType.set("fast-follow") }
}
