-keepattributes Signature
-keepattributes *Annotation*

# Firebase and Media3 publish their consumer rules. Keep model constructors
# because Firestore snapshots are also inspected reflectively on some devices.
-keep class com.ali.menbaradkshk.data.** { *; }

# عمّال WorkManager يُنشأون انعكاسياً بالاسم عند الإقلاع (التحميل الخلفي،
# الوِرد اليومي، تذكير المتابعة، التنزيل التلقائي). حذفهم يعطّل هذه الميزات
# في نسخة الإصدار وحدها دون ظهور أي خطأ في نسخة التطوير.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.ali.menbaradkshk.notification.** { *; }

# مزوّد الودجت يُنشأ من النظام عبر المانيفست.
-keep class com.ali.menbaradkshk.widget.** { *; }
