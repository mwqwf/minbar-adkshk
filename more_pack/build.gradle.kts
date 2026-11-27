// 🎁 حزمة الأصول «more»: الجزء الثالث من المكتبة (≤492 م.ب) — on-demand مثل core وrest.
plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("more")
    dynamicDelivery { deliveryType.set("on-demand") }
}
