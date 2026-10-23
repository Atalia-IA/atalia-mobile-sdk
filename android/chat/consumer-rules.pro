# API pública do SDK (chamada por apps Java/Kotlin, React Native e Flutter).
-keep class ai.atalia.chat.Atalia { *; }
-keep class ai.atalia.chat.AtaliaOptions { *; }
-keep class ai.atalia.chat.AtaliaEvent { *; }
-keep class ai.atalia.chat.AtaliaEvent$* { *; }
-keep class ai.atalia.chat.AtaliaContact { *; }
-keep class ai.atalia.chat.AtaliaException { *; }
-keep interface ai.atalia.chat.AtaliaListener { *; }
-keep interface ai.atalia.chat.AtaliaCallback { *; }

# Bridge legado (WebView sem WebMessageListener): o nome do método é chamado pelo JavaScript.
-keepclassmembers class ai.atalia.chat.internal.LegacyBridge {
    @android.webkit.JavascriptInterface <methods>;
}
