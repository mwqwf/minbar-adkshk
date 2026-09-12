// 🎁 حزمة الأصول «rest»: بقيّة المكتبة — fast-follow بعد core.
plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("rest")
    dynamicDelivery { deliveryType.set("fast-follow") }
}
