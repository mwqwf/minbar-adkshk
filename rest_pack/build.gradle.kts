// 🎁 حزمة الأصول «rest»: بقيّة المكتبة — يطلبها التطبيق عبر Play دون بدء إجباري من المتجر.
plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("rest")
    dynamicDelivery { deliveryType.set("on-demand") }
}
